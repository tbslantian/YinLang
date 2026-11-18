#import "AudioTap.h"
#import "AudioDSP.h"
#import <MediaToolbox/MediaToolbox.h>
#include <stdlib.h>
typedef struct { YLDSP *dsp; BOOL interleaved,valid; unsigned channels; } TapState;
static void tapInit(MTAudioProcessingTapRef tap,void *clientInfo,void **storage) { *storage=calloc(1,sizeof(TapState)); }
static void tapFinalize(MTAudioProcessingTapRef tap) { TapState *s=MTAudioProcessingTapGetStorage(tap); if(s){YLDestroyDSP(s->dsp);free(s);} }
static void tapPrepare(MTAudioProcessingTapRef tap,CMItemCount maxFrames,const AudioStreamBasicDescription *format) {
    TapState *s=MTAudioProcessingTapGetStorage(tap); if(!s)return;
    s->valid=format->mFormatID==kAudioFormatLinearPCM&&(format->mFormatFlags&kAudioFormatFlagIsFloat)&&format->mBitsPerChannel==32&&format->mChannelsPerFrame<=2;
    s->interleaved=!(format->mFormatFlags&kAudioFormatFlagIsNonInterleaved);s->channels=format->mChannelsPerFrame;
    YLDestroyDSP(s->dsp);s->dsp=s->valid?YLCreateDSP(format->mSampleRate,s->channels):NULL;
}
static void tapUnprepare(MTAudioProcessingTapRef tap) { TapState *s=MTAudioProcessingTapGetStorage(tap);if(s){YLDestroyDSP(s->dsp);s->dsp=NULL;} }
static void tapProcess(MTAudioProcessingTapRef tap,CMItemCount frames,MTAudioProcessingTapFlags flags,AudioBufferList *buffers,CMItemCount *outFrames,MTAudioProcessingTapFlags *outFlags) {
    if(MTAudioProcessingTapGetSourceAudio(tap,frames,buffers,outFlags,NULL,outFrames)!=noErr){*outFrames=0;return;}
    TapState *s=MTAudioProcessingTapGetStorage(tap);if(!s||!s->valid||!s->dsp||!*outFrames||!buffers->mNumberBuffers)return;
    float *left=buffers->mBuffers[0].mData,*right=NULL;if(!left)return;
    if(s->channels==2)right=s->interleaved?left+1:(buffers->mNumberBuffers>1?buffers->mBuffers[1].mData:NULL);
    YLProcessDSP(s->dsp,left,right,*outFrames,s->interleaved?s->channels:1);
}
BOOL YLAttachAudioEffects(AVPlayerItem *item,AVAssetTrack *track) {
    MTAudioProcessingTapCallbacks callbacks={kMTAudioProcessingTapCallbacksVersion_0,NULL,tapInit,tapFinalize,tapPrepare,tapUnprepare,tapProcess};
    MTAudioProcessingTapRef tap=NULL;
    if(MTAudioProcessingTapCreate(kCFAllocatorDefault,&callbacks,kMTAudioProcessingTapCreationFlag_PostEffects,&tap)!=noErr)return NO;
    AVMutableAudioMixInputParameters *parameters=[AVMutableAudioMixInputParameters audioMixInputParametersWithTrack:track];parameters.audioTapProcessor=tap;
    AVMutableAudioMix *mix=[AVMutableAudioMix audioMix];mix.inputParameters=@[parameters];item.audioMix=mix;CFRelease(tap);return YES;
}
