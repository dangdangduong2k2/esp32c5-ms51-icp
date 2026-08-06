; SDCC MCS-51 startup override for MS51FC0AE.
;
; The application data ends below 0x70. MS51FC0AE has 256 bytes of internal
; RAM, so SP=0x70 makes the first PUSH land safely at 0x71. This replaces
; SDCC's default crtstart.rel while retaining its normal early-startup flow.

        .area CSEG    (CODE)
        .area GSINIT0 (CODE)
        .area GSINIT1 (CODE)
        .area GSINIT2 (CODE)
        .area GSINIT3 (CODE)
        .area GSINIT4 (CODE)
        .area GSINIT5 (CODE)
        .area GSINIT  (CODE)
        .area GSFINAL (CODE)

        .globl ___sdcc_external_startup
        .globl __sdcc_program_startup

        .area GSINIT0 (CODE)
__sdcc_gsinit_startup::
        mov     sp,#0x70

        .area GSINIT2 (CODE)
        lcall   ___sdcc_external_startup
        mov     a,dpl
        jz      __sdcc_init_data
        ljmp    __sdcc_program_startup
__sdcc_init_data:
