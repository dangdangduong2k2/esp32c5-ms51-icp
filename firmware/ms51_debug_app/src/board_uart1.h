#ifndef MS51_DEBUG_BOARD_UART1_H
#define MS51_DEBUG_BOARD_UART1_H

/*
 * Board support for the MS51FC0AE telemetry demo.
 * UART1 TX is intentionally the only enabled UART direction.
 */
void board_clock_init_24mhz(void);
void board_uart1_init(void);
void board_uart1_write_byte(unsigned char byte);
void board_delay_ms(unsigned int ms);

#endif
