#include "../YinLang/Playback/AudioDSP.h"
#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
static double rms(const float *values,size_t count) { double sum=0;for(size_t i=count/2;i<count;i++)sum+=values[i]*values[i];return sqrt(sum/(count-count/2)); }
int main(void) {
    const size_t count=48000;
    float *left=calloc(count,sizeof(float)),*right=calloc(count,sizeof(float)),gains[10]={0};assert(left&&right);
    YLDSP *dsp=YLCreateDSP(48000,2);assert(dsp);
    for(size_t i=0;i<count;i++)left[i]=right[i]=.1*sin(2*M_PI*1000*i/48000);
    double original=rms(left,count);YLSetEffects(false,gains,0,0,0);YLProcessDSP(dsp,left,right,count,1);assert(fabs(rms(left,count)-original)<1e-7);
    gains[5]=6;YLSetEffects(true,gains,0,0,0);YLProcessDSP(dsp,left,right,count,1);assert(rms(left,count)>original*1.8&&rms(left,count)<original*2.2);
    YLDestroyDSP(dsp);dsp=YLCreateDSP(48000,2);assert(dsp);
    gains[5]=0;YLSetEffects(true,gains,.6,7,.8);
    for(size_t i=0;i<count;i++)left[i]=right[i]=.1*sin(2*M_PI*440*i/48000);
    YLProcessDSP(dsp,left,right,count,1);
    for(size_t i=0;i<count;i++){assert(isfinite(left[i])&&isfinite(right[i]));assert(fabs(left[i])<=1&&fabs(right[i])<=1);}
    YLDestroyDSP(dsp);free(left);free(right);puts("DSP bypass, frequency response, effects and output bounds passed.");return 0;
}
