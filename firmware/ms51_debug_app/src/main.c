#include "numicro_8051.h"

#include "board_uart1.h"
#include "ms51_debug_telemetry.h"

/*
 * Minimal MS51FC0AE application for testing the ESP debug web page.
 * It has no board-specific LED or sensor dependency.
 */
void main(void)
{
    unsigned int heartbeat = 0;

    board_clock_init_24mhz();
    board_uart1_init();
    ms51_debug_set_writer(board_uart1_write_byte);

    ms51_debug_log("ms51_debug_demo_boot");

    for (;;) {
        ms51_debug_var_u16("heartbeat", heartbeat);
        ms51_debug_var_u16("uptime_s", heartbeat);
        ms51_debug_var_u8("state", 1);

        ++heartbeat;
        board_delay_ms(1000);
    }
}
