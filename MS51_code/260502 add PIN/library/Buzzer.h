#ifndef BUZZER_H_
#define BUZZER_H_

#define Buzzer_Pin P11

bool          En_buzzer     = true;
bool          enable_Buzzer = false;
uint8_t xdata index_buzzer  = 0;
uint8_t xdata data_buzzer   = 0;

void Buzzer_Run();
void Buzzer_Set(uint8_t par);
void Buzzer_Set_Run(uint8_t par, uint16_t delay);
#endif
