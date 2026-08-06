#ifndef _GPIO_MANAGER_H_
#define _GPIO_MANAGER_H_

#include "Config_GPIO.h"
#include "MS51_GPIO_Macro.h"
#include "header.h"

#define SW2_PIN  IO06
#define DOWN_PIN IO05
#define UP_PIN   IO04
#define RL1_PIN  IO03
/* IO02 / P0.2 is UART1 RX while MS51 runs and returns to ICE_CLK for ICP. */
#define SDA2_PIN IO00
#define RL2_PIN  IO17
#define SET_PIN  IO14
#define SCL1_PIN IO12
#define SDA1_PIN IO11
#define SCL2_PIN IO10
#define RL3_PIN  IO30
// Old board: IO16. New board: IO01; IO16 is reserved for UART1 TX telemetry.
#define SIG_PIN  IO01

void    TM1_set_SCL(uint8_t value);
void    TM1_set_SDA(uint8_t value);
void    TM2_set_SCL(uint8_t value);
void    TM2_set_SDA(uint8_t value);
uint8_t TM2_read_SDA(void);

#endif
