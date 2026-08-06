#ifndef LED_STATUS_H_
#define LED_STATUS_H_

#define LED_Status_Pin P30

uint8_t xdata  index_led     = 0;
uint8_t xdata  delay_led     = 0;
uint8_t xdata  set_delay_led = 49;
uint16_t xdata led_time_uot  = 0;
uint16_t xdata data_led      = 5;

void Led_Status_Run(void);
void Led_Send_Data(void);

#endif
