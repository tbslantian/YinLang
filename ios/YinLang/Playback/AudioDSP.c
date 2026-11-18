#include "AudioDSP.h"
#include <math.h>
#include <stdlib.h>
#include <stdatomic.h>
#include <string.h>

#define DELAY 131072
#define MASK (DELAY - 1)
static _Atomic bool active = false;
static _Atomic float gainsDB[10], reverbWet, pitchSemitones, stereoWidth;
static const double frequencies[10] = {31,62,125,250,500,1000,2000,4000,8000,16000};
typedef struct { double b0,b1,b2,a1,a2,z1[2],z2[2]; } Biquad;
struct YLDSP { double rate,phase; unsigned channels,write; float last[10]; Biquad eq[10]; float *delay[2],*reverb[2]; };

void YLSetEffects(bool enabled, const float gains[10], float wet, float pitch, float surround) {
    for (int i=0;i<10;i++) atomic_store(&gainsDB[i], fmaxf(-12,fminf(12,gains[i])));
    atomic_store(&reverbWet,fmaxf(0,fminf(1,wet)));
    atomic_store(&pitchSemitones,fmaxf(-12,fminf(12,pitch)));
    atomic_store(&stereoWidth,fmaxf(0,fminf(1,surround)));
    atomic_store(&active,enabled);
}
YLDSP *YLCreateDSP(double rate, unsigned channels) {
    if (rate < 8000 || rate > 384000 || channels < 1 || channels > 2) return NULL;
    YLDSP *s = calloc(1,sizeof(YLDSP)); if (!s) return NULL;
    s->rate=rate; s->channels=channels;
    for(int c=0;c<2;c++) { s->delay[c]=calloc(DELAY,sizeof(float)); s->reverb[c]=calloc(DELAY,sizeof(float)); }
    if(!s->delay[0]||!s->delay[1]||!s->reverb[0]||!s->reverb[1]) { YLDestroyDSP(s); return NULL; }
    for(int i=0;i<10;i++) s->last[i]=999;
    return s;
}
void YLDestroyDSP(YLDSP *s) { if (!s) return; for(int c=0;c<2;c++){free(s->delay[c]);free(s->reverb[c]);}free(s); }
static float readDelay(float *data, double position) {
    int index=(int)floor(position); double frac=position-floor(position);
    return data[index&MASK]*(1-frac)+data[(index+1)&MASK]*frac;
}
void YLProcessDSP(YLDSP *s, float *left, float *right, size_t frames, size_t stride) {
    if(!s||!left||!stride||!atomic_load(&active))return;
    for(int i=0;i<10;i++) {
        float gain=atomic_load(&gainsDB[i]); if(gain==s->last[i])continue; s->last[i]=gain;
        double a=pow(10,gain/40), w=2*M_PI*fmin(frequencies[i],s->rate*.45)/s->rate;
        double alpha=sin(w)/(2*1.41421356237), a0=1+alpha/a;
        Biquad *q=&s->eq[i]; q->b0=(1+alpha*a)/a0; q->b1=-2*cos(w)/a0; q->b2=(1-alpha*a)/a0; q->a1=q->b1; q->a2=(1-alpha/a)/a0;
    }
    const double pitch=atomic_load(&pitchSemitones), ratio=pow(2,pitch/12), window=s->rate*.05;
    const float wet=atomic_load(&reverbWet), width=atomic_load(&stereoWidth);
    const int taps[4]={(int)(s->rate*.0297),(int)(s->rate*.0371),(int)(s->rate*.0411),(int)(s->rate*.0437)};
    for(size_t f=0;f<frames;f++) {
        float sample[2]={left[f*stride],right?right[f*stride]:left[f*stride]};
        for(unsigned c=0;c<s->channels;c++) {
            double x=sample[c]; if(!isfinite(x))x=0;
            for(int i=0;i<10;i++) { Biquad *q=&s->eq[i]; double y=q->b0*x+q->z1[c];q->z1[c]=q->b1*x-q->a1*y+q->z2[c];q->z2[c]=q->b2*x-q->a2*y;x=y; }
            s->delay[c][s->write]=x;
            if(fabs(pitch)>.001) {
                double phase2=fmod(s->phase+.5,1), weight=.5-.5*cos(2*M_PI*s->phase);
                x=readDelay(s->delay[c],s->write-(32+s->phase*window))*weight + readDelay(s->delay[c],s->write-(32+phase2*window))*(1-weight);
            }
            double echo=0; for(int j=0;j<4;j++)echo+=s->reverb[c][(s->write-taps[j]-c*127)&MASK]*.25;
            s->reverb[c][s->write]=fmax(-2,fmin(2,x+echo*.67));
            sample[c]=x*(1-wet*.35)+echo*wet;
        }
        if(right&&s->channels==2&&width>0) {
            float delayedLeft=s->delay[0][(s->write-(unsigned)(s->rate*.0007))&MASK];
            float delayedRight=s->delay[1][(s->write-(unsigned)(s->rate*.0009))&MASK];
            sample[0]=(sample[0]-delayedRight*.3*width)/(1+.3*width);
            sample[1]=(sample[1]-delayedLeft*.3*width)/(1+.3*width);
        }
        // Keep full-scale output bounded without changing low-level samples.
        left[f*stride]=fmaxf(-1,fminf(1,sample[0])); if(right)right[f*stride]=fmaxf(-1,fminf(1,sample[1]));
        s->write=(s->write+1)&MASK;
        s->phase+= (1-ratio)/window; s->phase-=floor(s->phase);
    }
}
