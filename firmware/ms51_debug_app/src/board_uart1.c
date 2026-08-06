#include "numicro_8051.h"
#include "sys.h"

#include "board_uart1.h"

/*
 * Select the factory-calibrated 24 MHz HIRC. MODIFY_HIRC() is supplied by
 * Nuvoton's sys.c and copies the appropriate factory trim before switching.
 */
void board_clock_init_24mhz(void)
{
    FsysSelect(FSYS_HIRC);
    MODIFY_HIRC(HIRC_24);

    SFRS = 0;
    CKDIV = 0x00;
}

/*
 * UART1 TX-only, 115200 baud at 24 MHz.
 * P1.6 is also ICE_DAT: it cannot be used for live ICP/debug while this
 * firmware is transmitting.
 */
void board_uart1_init(void)
{
    SFRS = 0;

    /* P1.6 as quasi-bidirectional output. */
    P1 |= 0x40;
    P1M1 &= 0xBF;
    P1M2 &= 0xBF;

    /* Map UART1 TXD to P1.6 (AUXR2[3:2] = 01). */
    SFRS = 2;
    AUXR2 = (AUXR2 & 0xF3) | 0x04;
    SFRS = 0;

    /* Do not use UART1 RX or its interrupt: P0.2/ICE_CLK stays untouched. */
    EIE1 &= 0xFE;
    T3CON = 0x80;
    RH3 = 0xFF;
    RL3 = 0xF3;
    SCON_1 = 0x40;
    SCON_1 &= 0xFD;
    T3CON = 0x88;
}

void board_uart1_write_byte(unsigned char byte)
{
    SFRS = 0;
    SCON_1 &= 0xFD;
    SBUF_1 = byte;
    while ((SCON_1 & 0x02) == 0) {
    }
    SCON_1 &= 0xFD;
}

/* Blocking 1 ms ticks from Timer0: 24 MHz HIRC / 12 = 2 MHz. */
void board_delay_ms(unsigned int ms)
{
    SFRS = 0;
    CKCON &= 0xF7;
    TMOD = (TMOD & 0xF0) | 0x01;

    while (ms-- != 0) {
        TCON &= 0xCF;
        TH0 = 0xF8;
        TL0 = 0x30;
        TCON |= 0x10;
        while ((TCON & 0x20) == 0) {
        }
        TCON &= 0xCF;
    }
}
