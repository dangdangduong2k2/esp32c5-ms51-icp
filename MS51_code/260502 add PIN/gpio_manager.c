#include "gpio_manager.h"


/* Define Config IO By Config_IO */
void GPIO_INIT() {
/* ===== GPIO INIT =====
|  Port  |     P0     |     P1     |     P3     |
|  Px7   | Input-only |  Push-pull |            |
|  Px6   | Input-only | Input-only |            |
|  Px5   |    Quasi   | Input-only |            |
|  Px4   |    Quasi   |    Quasi   |            |
|  Px3   |  Push-pull | Input-only |            |
|  Px2   | Input-only |  Push-pull |            |
|  Px1   |    Quasi   |    Quasi   |            |
|  Px0   |    Quasi   |  Push-pull |  Push-pull |
===== GPIO INIT =====*/
/* ===== GPIO DEFAULT STATE =====
|  Port  | P0 | P1 | P3 |
|  Px7   |  0 |  1 |    |
|  Px6   |  0 |  1 |    |
|  Px5   |  1 |  0 |    |
|  Px4   |  1 |  1 |    |
|  Px3   |  1 |  0 |    |
|  Px2   |  1 |  1 |    |
|  Px1   |  1 |  1 |    |
|  Px0   |  1 |  1 |  0 |
 ===== GPIO DEFAULT STATE =====*/
P0=0x3F;
P0M1=0xC4;
/* P0.2 is input-only for UART1 RX; ESP drives it only outside ICP sessions. */
P0M2=0x08;
P1=0xD7;
P1M1=0x68;
P1M2=0x85;
P3=0x00;
P3M1=0x00;
P3M2=0x01;
}									



void    TM1_set_SCL(uint8_t value) { PIN_write(SCL1_PIN, value); }
void    TM1_set_SDA(uint8_t value) { PIN_write(SDA1_PIN, value); }
uint8_t TM1_read_SDA(void) { return PIN_read(SDA1_PIN); }

void    TM2_set_SCL(uint8_t value) { PIN_write(SCL2_PIN, value); }
void    TM2_set_SDA(uint8_t value) { PIN_write(SDA2_PIN, value); }
uint8_t TM2_read_SDA(void) { return PIN_read(SDA2_PIN); }

/*
 * Electrical level that energizes each relay output:
 *   1 = active-high relay board, 0 = active-low relay board.
 * Change only these three values for a board with different relay drivers.
 */
#define RL1_ACTIVE_LEVEL 1u
#define RL2_ACTIVE_LEVEL 1u
#define RL3_ACTIVE_LEVEL 1u

#if ((RL1_ACTIVE_LEVEL != 0u) && (RL1_ACTIVE_LEVEL != 1u)) || \
	((RL2_ACTIVE_LEVEL != 0u) && (RL2_ACTIVE_LEVEL != 1u)) || \
	((RL3_ACTIVE_LEVEL != 0u) && (RL3_ACTIVE_LEVEL != 1u))
#error Relay active levels must be 0 or 1.
#endif

#define RL_OUTPUT_LEVEL(active_level, is_on) \
	((is_on) ? (active_level) : ((active_level) ? 0u : 1u))

#define RL1_ON()      do { PIN_write(RL1_PIN, RL_OUTPUT_LEVEL(RL1_ACTIVE_LEVEL, 1u)); RL1_is_on = 1; RL1_output_is_on = 1; } while (0)
#define RL1_OFF()    	do { if (RL1_is_on) { RL2_is_on = 0; } PIN_write(RL1_PIN, RL_OUTPUT_LEVEL(RL1_ACTIVE_LEVEL, 0u)); RL1_is_on = 0; RL1_output_is_on = 0; } while (0)
#define RL1_TOGGLE() 	PIN_toggle(RL1_PIN)
#define RL1_SET(s)   	PIN_write(RL1_PIN, s)

#define RL2_ON()      do { PIN_write(RL2_PIN, RL_OUTPUT_LEVEL(RL2_ACTIVE_LEVEL, 1u)); RL2_output_is_on = 1; } while (0)
#define RL2_OFF()    	do { PIN_write(RL2_PIN, RL_OUTPUT_LEVEL(RL2_ACTIVE_LEVEL, 0u)); RL2_output_is_on = 0; } while (0)
#define RL2_TOGGLE()	PIN_toggle(RL2_PIN)
#define RL2_SET(s)   	PIN_write(RL2_PIN, s)

#define RL3_ON()      do { PIN_write(RL3_PIN, RL_OUTPUT_LEVEL(RL3_ACTIVE_LEVEL, 1u)); RL3_output_is_on = 1; } while (0)
#define RL3_OFF()    	do { PIN_write(RL3_PIN, RL_OUTPUT_LEVEL(RL3_ACTIVE_LEVEL, 0u)); RL3_output_is_on = 0; } while (0)
#define RL3_TOGGLE() 	PIN_toggle(RL3_PIN)
#define RL3_SET(s)   	PIN_write(RL3_PIN, s)
