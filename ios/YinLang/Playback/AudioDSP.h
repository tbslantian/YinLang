#pragma once
#include <stddef.h>
#include <stdbool.h>
typedef struct YLDSP YLDSP;
void YLSetEffects(bool enabled, const float gains[10], float wet, float pitch, float surround);
void YLSetEnvironment(float dry, float roomScale, float feedback);
YLDSP *YLCreateDSP(double rate, unsigned channels);
void YLDestroyDSP(YLDSP *state);
void YLProcessDSP(YLDSP *state, float *left, float *right, size_t frames, size_t stride);
