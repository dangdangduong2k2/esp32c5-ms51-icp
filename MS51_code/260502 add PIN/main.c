#define version 10 // 1.0
#include "main.h"

bool adjust_parameter(uint32_t *parameter, uint32_t min, uint32_t max, uint8_t btnDOWN, uint8_t btnUP, bool rotate, uint8_t step)
{
	bool change = false;
	if (button_released(btnDOWN))
	{
	label_Sub:
		if (*parameter > min)
		{
			if (*parameter >= min + step)
			{
				(*parameter) -= step;
			}
			else
			{
				*parameter = min;
			}
			change = true;
		}
		else if (rotate)
		{
			*parameter = max;
			change = true;	
		}
	}
	if (button_hold(btnDOWN, 10))
	{
		btn[btnDOWN].holdTime -= 2;
		btn[btnDOWN].event = EMPTY;
		goto label_Sub;
	}
	if (button_released(btnUP))
	{
	label_add:
		if (*parameter < max)
		{
			if (*parameter <= max - step)
				{
				(*parameter) += step;
			}
			else
			{
				*parameter = max;
			}
			change = true;
		}
		else if (rotate)
		{
			*parameter = min;
			change = true;
		}
	}
	if (button_hold(btnUP, 10))
	{
		btn[btnUP].holdTime -= 2;
		btn[btnUP].event = EMPTY;
		goto label_add;
	}
	return change;
}
void display_set_enable(uint8_t idx, bool state)
{
	switch (idx)
	{
	case CL:
		TM1650_SET_ENABLE(&RED, state);
		break;
	case OP:
		TM1650_SET_ENABLE(&GREEN, state);
		break;
	default:
		break;
	}
}
void display_num(uint8_t idx, uint16_t num)
{
	switch (idx)
	{
	case CL:
		TM1650_number(&RED, num);
		break;
	case OP:
		TM1650_number(&GREEN, num);
		break;
	default:
		break;
	}
}
void display_3digit(uint16_t num)
{
	num %= 1000;
	TM1650_write_n_bytes(&RED, 3, num_hex[num % 10], num_hex[(num / 10) % 10], num_hex[(num / 100) % 10]);
}
void display_string(uint8_t idx, uint8_t *s)
{
	switch (idx)
	{
	case CL:
		TM1650_string(&RED, s);
		break;
	case OP:
		TM1650_string(&GREEN, s);
		break;
	default:
		break;
	}
}
void display_float(uint8_t idx, uint16_t num)
{
	uint8_t buf[3];
	uint8_t i;
	num %= 1000;
	for (i = 0; i < 3; i++)
	{
		buf[i] = seg_map[num % 10];
		num /= 10;
	}
	buf[1] |= sMap(DOT);
	if (buf[2] == sMap(0))
		buf[2] = sMap(OFF);

	switch (idx)
	{
	case CL:
		TM1650_write_n_bytes(&RED, 3, buf[0], buf[1], buf[2]);
		break;
	case OP:
		TM1650_write_n_bytes(&GREEN, 3, buf[0], buf[1], buf[2]);
		break;
	default:
		break;
	}
}
bool readButtonState(uint8_t btn)
{
	if (btn >= btnTotal)
		return 0;
	switch (btn)
	{
	case SET:
		return PIN_read(SET_PIN);
		break;
	case UP:
		return PIN_read(UP_PIN);
		break;
	case DOWN:
		return PIN_read(DOWN_PIN);
		break;
	default:
		break;
	}

	return 0;
}
void default_state(void)
{
	RL1_OFF();
	RL2_OFF();
	RL3_OFF();
}

uint8_t password_request_mark_value(void)
{
	uint8_t mark = password_request_mark;
	mark ^= (uint8_t)eData.pin_password;
	mark ^= (uint8_t)(eData.pin_password >> 8);
	mark ^= (uint8_t)eData.pin_period_day;
	mark ^= (uint8_t)(eData.pin_period_day >> 8);
	mark ^= eData.pin_period_unit;
	mark ^= eData.pin_enable;
	mark ^= eData.pin_wrong_limit;
	return mark;
}

uint16_t password_period_to_hours(uint16_t period, uint8_t unit)
{
	uint32_t target = period;
	if (unit)
	{
		target *= 24;
	}
	if (target > 65535UL)
	{
		target = 65535UL;
	}
	return (uint16_t)target;
}

uint16_t password_request_target_count(void)
{
	return password_period_to_hours(eData.pin_period_day, eData.pin_period_unit);
}

uint16_t password_backup_target_count(void)
{
	return password_period_to_hours(eData.pin_backup_period_day, eData.pin_backup_period_unit);
}

void password_request_clear(void)
{
	eData.pin_request = 0;
	eData.pin_request_mark = 0;
	eData.pin_request_mark_inv = 0;
}

void password_request_set(void)
{
	uint8_t mark = password_request_mark_value();
	eData.pin_day_count = password_request_target_count();
	eData.pin_request = 1;
	eData.pin_request_mark = mark;
	eData.pin_request_mark_inv = (uint8_t)(~mark);
}

bool password_request_valid(void)
{
	uint8_t mark = password_request_mark_value();
	return (eData.pin_request &&
			eData.pin_period_day &&
			eData.pin_day_count == password_request_target_count() &&
			eData.pin_request_mark == mark &&
			eData.pin_request_mark_inv == (uint8_t)(~mark));
}

void eeprom_init(void)
{
	uint8_t i = 0;
	bool update = false;
	/* This target is configured for 32 KB APROM.  Keep the final 256 bytes as
	 * EEPROM at 0x7F00 so the runtime layout exactly matches app.py. */
	AP_EEPROM_Init(Flash_Total_32KB, Flash_Data_256B, (uint8_t *)&eData, sizeof(eData));
	if (eData.init != eeprom_init_val)
	{
		eData.init = eeprom_init_val;
		eData.time[COOL][CL] = 1;
		eData.time[COOL][OP] = 1;
		eData.time[ICE_FLUSH][CL] = 1;
		eData.time[ICE_FLUSH][OP] = 1;
		eData.delayST = 70;
		eData.DF = 1;
		eData.END = 1;
		eData.first_progress1 = OP; // chay lanh
		eData.first_progress2 = OP; // xa da
		eData.mode = LED;
		
		eData.lock_time = 0;
		eData.led_bri[LED_RED] = 0;
		eData.led_bri[LED_GREEN] = 0;
		eData.HCF = 1;
		eData.on_time = 0;
		eData.touch_num = 1;
		eData.try_time = 0;
		eData.check_box[ICE_FLUSH_OP_TIME] = 1;
		eData.check_box[HIDE_MODE_DF] = 1;
		eData.check_box[HIDE_MODE_END] = 1;
		eData.check_box[HIDE_MODE_SL] = 1;
		eData.check_box[HIDE_MODE_HCF] = 1;
		eData.check_box[HIDE_MODE_HDF] = 1;
		eData.check_box[TRY_TIME_UNIT] = 0;
		eData.shutdown = 0;
		eData.tried_time = 0;
		eData.HCF_time = 10;
		eData.sig_index = 0;

		for (i = 0; i < HIS_MAX; i++)
		{
			eData.sig_count[i] = 0;
		}
		eData.hDF = 0;
		
		
		eData.time_min[COOL][CL]=1;
		eData.time_min[COOL][OP]=1;
		
		eData.time_min[ICE_FLUSH][CL]=1;
		eData.time_min[ICE_FLUSH][OP]=1;
		
		eData.time_min[ST][LED]=1;
		eData.time_min[ST][LCD]=1;
		
		eData.time_max[COOL][CL]=999;
		eData.time_max[COOL][OP]=999;
		
		eData.time_max[ICE_FLUSH][CL]=200;	
		eData.time_max[ICE_FLUSH][OP]=60;
		
		eData.time_max[ST][LED]=90;
		eData.time_max[ST][LCD]=90;
		
		eData.time_delay = 30;
		eData.hide_st=1;
		
		eData.ST_LCD = 50;
		eData.ST_LED = 50;
		eData.pin_password = 123;
		eData.pin_period_day = 1;
		eData.pin_day_count = 0;
		eData.pin_request = 0;
		eData.pin_backup_password = 999;
		eData.pin_locked = 0;
		eData.pin_backup_used = 0;
		eData.pin_backup_period_day = 1;
		eData.pin_period_unit = 0;
		eData.pin_backup_period_unit = 0;
		eData.pin_enable = 1;
		eData.pin_period_show = 1;
		eData.pin_wrong_limit = 3;
		eData.pin_request_mark = 0;
		eData.pin_request_mark_inv = 0;
		
		AP_EEPROM_Commit();
	}
	if (eData.pin_enable > 1)
	{
		eData.pin_enable = 1;
		update = true;
	}
	if (!eData.pin_enable)
	{
		if (eData.pin_password || eData.pin_period_day || eData.pin_day_count || eData.pin_request ||
			eData.pin_backup_password || eData.pin_locked || eData.pin_backup_used || eData.pin_backup_period_day ||
			eData.pin_period_unit || eData.pin_backup_period_unit || eData.pin_period_show || eData.pin_wrong_limit ||
			eData.pin_request_mark || eData.pin_request_mark_inv)
		{
			eData.pin_password = 0;
			eData.pin_period_day = 0;
			eData.pin_day_count = 0;
			eData.pin_backup_password = 0;
			eData.pin_locked = 0;
			eData.pin_backup_used = 0;
			eData.pin_backup_period_day = 0;
			eData.pin_period_unit = 0;
			eData.pin_backup_period_unit = 0;
			eData.pin_period_show = 0;
			eData.pin_wrong_limit = 0;
			password_request_clear();
			update = true;
		}
	}
	else
	{
	if (eData.pin_password > password_max_value)
	{
		eData.pin_password = 0;
		update = true;
	}
	if (eData.pin_period_day > password_max_value)
	{
		eData.pin_period_day = 0;
		update = true;
	}
	if (eData.pin_request > 1)
	{
		password_request_clear();
		update = true;
	}
	if (eData.pin_backup_password == 0 || eData.pin_backup_password > password_max_value)
	{
		eData.pin_backup_password = 999;
		eData.pin_locked = 0;
		update = true;
	}
	if (eData.pin_locked > 1)
	{
		eData.pin_locked = 0;
		update = true;
	}
	if (eData.pin_backup_used > 1)
	{
		eData.pin_backup_used = 0;
		update = true;
	}
	if (eData.pin_backup_period_day == 0 || eData.pin_backup_period_day > password_max_value)
	{
		eData.pin_backup_period_day = 1;
		update = true;
	}
	if (eData.pin_period_unit > 1)
	{
		eData.pin_period_unit = 0;
		update = true;
	}
	if (eData.pin_backup_period_unit > 1)
	{
		eData.pin_backup_period_unit = 0;
		update = true;
	}
	if (eData.pin_period_show > 1)
	{
		eData.pin_period_show = 1;
		update = true;
	}
	if (eData.pin_wrong_limit == 0 || eData.pin_wrong_limit > 99)
	{
		eData.pin_wrong_limit = 3;
		update = true;
	}
	if (eData.pin_request && !password_request_valid())
	{
		eData.pin_day_count = 0;
		password_request_clear();
		update = true;
	}
	else if (!eData.pin_request && (eData.pin_request_mark || eData.pin_request_mark_inv))
	{
		password_request_clear();
		update = true;
	}
	if (eData.pin_backup_used)
	{
		password_request_clear();
		if (!eData.pin_locked && eData.pin_day_count >= password_backup_target_count())
		{
			eData.pin_day_count = password_backup_target_count() - 1;
			update = true;
		}
	}
	else if (eData.pin_period_day == 0 && (eData.pin_request || eData.pin_day_count))
	{
		eData.pin_day_count = 0;
		password_request_clear();
		update = true;
	}
	if (!eData.pin_backup_used && eData.pin_period_day && !eData.pin_request && eData.pin_day_count >= password_request_target_count())
	{
		eData.pin_day_count = password_request_target_count() - 1;
		update = true;
	}
	}
	if (update)
	{
		AP_EEPROM_Commit();
	}
}

void display_init(void)
{
	uint8_t i = 0;
	TM1650_init(&GREEN, 3, 0x6c);
	TM1650_Set_Brightness(&GREEN, TM1650_BRN[eData.led_bri[LED_GREEN]]);
	TM1650_update(&GREEN);

	TM1650_init(&RED, 3, 0x6c);
	TM1650_Set_Brightness(&RED, TM1650_BRN[eData.led_bri[LED_RED]]);
	TM1650_update(&RED);
}

uint16_t adc_average(uint8_t chanel)
{
	uint32_t result = 0;
	uint8_t i;
	for (i = 3; i < adc_count_max; i++)
	{
		result += ADC_Result[chanel][i];
	}
	result /= (adc_count_max - 3);
	return (uint16_t)result;
}

void ADC_update(void)
{
	static TICK_TYPE tick;
	uint32_t result = 0;
	switch_t state;
	static uint8_t state_count = 0;

	if (adc_done)
	{
		adc_done = false;
		ADC_Start();
	}

	if (tick_timeout(&tick, 50))
	{
		adc_avg[1] = adc_average(1);
		vdd = Convert_VDD(adc_avg[1]);

		adc_avg[0] = adc_average(0);

		if (adc_avg[0] < 1024)
		{
			state = SW_COOL;
		}
		else if (adc_avg[0] < 3072)
		{
			state = SW_IDLE;
		}
		else
		{
			state = SW_ICE_FLUSH;
		}

		switch_state = state;

		if (switch_state != state)
		{
			if (++state_count > 2)
			{
				switch_state = state;
				state_count = 0;
			}
		}
		else
		{
			state_count = 0;
		}

		// DBG_MSG("\r\nadc: ");
		// DBG_NUM(adc_avg[0]);

		if (adc_stable_count < 5)
		{
			++adc_stable_count;
		}
	}
}

bool adc_is_stable(void)
{
	if (adc_stable_count >= 5)
		return true;
	return false;
}

void ADC_INIT(void)
{
	my_memset(ADC_Result, 0, sizeof(ADC_Result));
	ADC_Set_Enable(true);
	READ_BAND_GAP();

	EADC = 1; // Enable ADC Interrupt
	ADCCON1 &= CLR_BIT1;
	adc_channel = 0;
	ADC_Set_Channel(adc_channel_idx[adc_channel]);
	ADC_Start();

	while (!adc_is_stable())
	{
		ADC_update();
		Clear_WDT();
	}
}

void switch_program(program_t pr)
{
	program = pr;
	program_init = false;
	button_reset(btnTotal);
	RL1_OFF();
	RL2_OFF();
	RL3_OFF();
	lock = LOCK_OFF;
	RL2_is_on = 0;
	RL2_LED_skip_on = 0;
	RL2_LED_had_on = 0;
	RL3_sig_is_on = 0;
	lock_timeout_refresh();
	popup_timeout = 0;
	hcf_mode_timeout = 0;
	hcf_mode = false;
	ticks_reset(&tick_hcf);
}

void countdown_timer(void)
{
	uint32_t time;
	uint8_t i;
	static uint8_t lock_reset_value = 0;
	static uint8_t lock_increase_sig_index = 1;
	if (tick_timeout(&tick, 100))
	{
		if (hcf_mode_timeout == 0)
		{
			if (--countdown == 0)
			{
				progress ^= 1;
//260419
				RL2_is_on = 0;
				RL2_LED_skip_on = 0;
				RL2_LED_had_on = 0;
				is_delay=0;
				
				ticks_reset(&tick_hcf);
				if (switch_state == SW_ICE_FLUSH && !eData.hDF && eData.check_box[ICE_FLUSH_OP_TIME] && progress == OP)
				{
					countdown = eData.time[program][progress];
					countdown *= 10;
					TM1650_number(&RED, eData.time[program][CL]);
				}
				else
				{
					if ((switch_state == SW_COOL || (switch_state == SW_ICE_FLUSH && eData.hDF)) && eData.HCF)
					{
						if (!eData.hDF)
						{
							if (progress == OP)
							{
								countdown = eData.HCF_time;
								countdown *= 600;
								TM1650_number(&RED, eData.time[program][CL]);
							}
							else
							{
								countdown = eData.time[program][progress];
								countdown *= 600;
								TM1650_number(&GREEN, 0);
							}
						}
						else
						{
							if (progress == OP)
							{
								countdown = eData.HCF_time;
								countdown *= 600;
								TM1650_number(&RED, eData.time[COOL][CL]);
							}
							else
							{
								countdown = eData.time[COOL][CL];
								countdown *= 600;
								TM1650_number(&GREEN, 0);
							}
						}
					}
					else
					{
						if(!eData.hDF)
						{
							countdown = eData.time[program][progress];
							countdown *= 600;
							TM1650_number(&RED, eData.time[program][CL]);
							TM1650_number(&GREEN, eData.time[program][OP]);
						}
						else
						{
							countdown = eData.time[COOL][progress];
							countdown *= 600;
							TM1650_number(&RED, eData.time[COOL][CL]);
							TM1650_number(&GREEN, eData.time[COOL][OP]);
						}
					}
				}
			}
			else
			{ //***hien thi
				if (switch_state == SW_ICE_FLUSH && !eData.hDF && eData.check_box[ICE_FLUSH_OP_TIME] && progress == OP)
				{
					time = countdown / 10;
					TM1650_number(&GREEN, time);
				}
				else
				{
					time = countdown / 600;
					if (countdown / 10 % 60)
						time += 1;
					if (progress == OP)
					{
						if (eData.HCF && (switch_state == SW_IDLE || switch_state == SW_COOL || (switch_state == SW_ICE_FLUSH && eData.hDF)))
						{
							TM1650_number(&RED, eData.time[COOL][CL]);
							TM1650_number(&GREEN, 0);
						}
						else
						{
							TM1650_number(&GREEN, time);
							if (eData.hDF)
								TM1650_number(&RED, eData.time[COOL][CL]);
							else
								TM1650_number(&RED, eData.time[program][CL]);
						}
					}
					else
					{
						TM1650_number(&RED, time);
						if (eData.HCF && (switch_state == SW_IDLE || switch_state == SW_COOL || (switch_state == SW_ICE_FLUSH && eData.hDF)))
						{
							TM1650_number(&GREEN, 0);
						}
						else
						{
							if (eData.hDF) TM1650_number(&GREEN, eData.time[COOL][OP]);
							else TM1650_number(&GREEN, eData.time[program][OP]);
						}
					}
				}
			}
			
		}
		// 250326 check OP chay lanh
		if (switch_state == SW_COOL || (switch_state == SW_ICE_FLUSH && eData.hDF))
		{
			if (eData.DF)
			{
				if ((progress == OP && eData.DF) || (progress == CL && hcf_mode_timeout && eData.DF))
				{
					if (--countdown_RL3 == 0)
					{ // dem du time OP chay Cl, du CL chay OP,reset OP CL time
						progress_RL3 ^= 1;
						if (eData.check_box[ICE_FLUSH_OP_TIME] && progress_RL3 == OP)
						{
							countdown_RL3 = eData.time[ICE_FLUSH][progress_RL3];
							countdown_RL3 *= 10;
						}
						else
						{
							countdown_RL3 = eData.time[ICE_FLUSH][progress_RL3];
							countdown_RL3 *= 600;
						}
					}
				}
				else
				{
					progress_RL3 = eData.first_progress2;
					if (eData.check_box[ICE_FLUSH_OP_TIME] && progress_RL3 == OP)
					{
						countdown_RL3 = eData.time[ICE_FLUSH][progress_RL3];
						countdown_RL3 *= 10;
					}
					else
					{
						countdown_RL3 = eData.time[ICE_FLUSH][progress_RL3];
						countdown_RL3 *= 600;
					}
				}
			}
		}
		else
		{ // khong phai OP chay lanh, reset time RL3 lien tuc moi 100ms
			if (eData.DF)
			{
				progress_RL3 = eData.first_progress2;
				if (eData.check_box[ICE_FLUSH_OP_TIME] && progress_RL3 == OP)
				{
					countdown_RL3 = eData.time[ICE_FLUSH][progress_RL3];
					countdown_RL3 *= 10;
				}
				else
				{
					countdown_RL3 = eData.time[ICE_FLUSH][progress_RL3];
					countdown_RL3 *= 600;
				}
			}
		}
		// 250326
	}
	if (hcf_mode_timeout)
	{
		if (lock_increase_sig_index)
		{
			lock_increase_sig_index = 0;
		}
		TM1650_number(&RED, eData.time[COOL][CL]);
		TM1650_string(&GREEN, "---");
		if (count_log && !lock_reset_value)
		{
			count_log = 0;
			lock_reset_value = 1;
		}
		if (tick_timeout(&tick_sig, 100))
		{
			count_log++;
		}
		
	}
	else
	{
		if (!lock_increase_sig_index)
		{
			if(count_log!=0)
			{		
				eData.sig_index++;
				if (eData.sig_index > (HIS_MAX-1))
				{
					eData.sig_index = 0;
				}
				for (i = eData.sig_index; i > 0; i--)
				{
					eData.sig_count[i] = eData.sig_count[i - 1];
				}
				eData.sig_count[0] = count_log;
			}
			else
			{
      }
			AP_EEPROM_Commit();
			lock_increase_sig_index = 1;
			lock_reset_value = 0;
		}
	}
}
void open_time_t(void)
{
	if (enable_open_time)
	{
		if (tick_timeout(&tick_open_time, 100))
		{
//			if (test)
			if (!PIN_read(SIG_PIN))
			{
				sig_time++;
			}
			open_time++;
			if (open_time >= 250)
			{
				if (sig_time >= 200)
				{
					hcf_mode_timeout = 1;
					open_time_is_sucess = 1;
				}
				else
				{
					if (progress == OP)
					{
						if (eData.mode == LCD)
						{
							countdown = 1;
						}
						else
						{
							switch (eData.on_time)
							{
							case 1:
								countdown = (ST_OFFSET * 2 + 10);
								break;
							case 0:
								countdown = ST_OFFSET;
								break;
							case 3:
								countdown = (ST_OFFSET * 2 + 10);
								break;
							case 2:
								countdown = ST_OFFSET;
								break;
							default:
								break;
							}
						}
					}
					else
					{
						countdown = eData.time[COOL][CL];
						countdown *= 600;
						run_hcf_CL_timeout_sig = 1; 
					}
					hcf_mode_timeout = 0;
					open_time_is_sucess = 0;
				}
				enable_open_time = 0;
				sig_time = 0;
				open_time = 0;
				ticks_reset(&tick_open_time);
			}
		}
	}
	else
	{
		open_time_is_sucess = 0;
	}
}
uint32_t a,b;
void countdown_init(void)
{
	TM1650_number(&GREEN, eData.time[program][OP]);
	TM1650_number(&RED, eData.time[program][CL]);
	ticks_reset(&tick);
	
	countdown_delay_RL3=eData.time_delay*10;
	if (switch_state == SW_COOL || (switch_state == SW_ICE_FLUSH && eData.hDF))
	{
		
		if (eData.HCF)
		{
			progress = eData.first_progress1;
			if (progress == OP)
			{
				countdown = eData.HCF_time;
				countdown *= 600;
			}
			else
			{
				countdown = eData.time[COOL][CL];
				countdown *= 600;
			}
		}
		else
		{
			progress = eData.first_progress1;
			countdown = eData.time[COOL][progress];
			countdown *= 600;
		}
		// 250326 init RL3 time = time xa da
		progress_RL3 = eData.first_progress2;
		if (eData.check_box[ICE_FLUSH_OP_TIME] && progress_RL3 == OP)
		{
			countdown_RL3 = eData.time[ICE_FLUSH][progress_RL3];
			countdown_RL3 *= 10;
		}
		else
		{
			countdown_RL3 = eData.time[ICE_FLUSH][progress_RL3];
			countdown_RL3 *= 600;
		}
	}
	else if (switch_state == SW_ICE_FLUSH && !eData.hDF)
	{
		progress = eData.first_progress2;
		if (eData.check_box[ICE_FLUSH_OP_TIME] && progress == OP)
		{
			countdown = eData.time[program][progress];
			countdown *= 10;
		}
		else
		{
			countdown = eData.time[program][progress];
			countdown *= 600;
		}
	}
	is_delay=0;
}

void RL2_ST_event_update(void)
{
	ST_OFFSET = 0;
	if (eData.HCF)
	{
		event_ST_on = eData.HCF_time;
		event_ST_on *= 600; // convert to 100ms unit
	}
	else
	{
		event_ST_on = eData.time[COOL][OP];
		event_ST_on *= 600; // convert to 100ms unit
	}
	if (eData.mode == LCD)
	{
		event_ST_on -= eData.delayST;
		ST_OFFSET = eData.ST_LCD;
	}
	if (eData.mode == LED)
	{
		ST_OFFSET = eData.ST_LED;
		if (ST_OFFSET < 25)
		{
			ST_OFFSET = 25;
		}
	}
	event_ST_off = event_ST_on - ST_OFFSET;
}

void RL2_ST_control(bool in_event)
{
	if (in_event)
	{
		if (!RL2_is_on)
		{
			RL2_ON();
			RL2_is_on = 1;
		}
	}
	else
	{
		RL2_OFF();
	}
}

void RL2_ST_LED_control(bool in_event)
{
	if (in_event)
	{
		if (RL2_LED_skip_on)
		{
			RL2_OFF();
		}
		else
		{
			RL2_ON();
			RL2_LED_had_on = 1;
		}
	}
	else
	{
		RL2_OFF();
	}
}

void RL3_DF_ON(void)
{
	RL3_ON();
	if (hcf_mode_timeout)
	{
		RL3_sig_is_on = 1;
	}
}

void RL3_DF_OFF(void)
{
	RL3_OFF();
}

void RL3_DF_reset_if_sig_idle(void)
{
//	if (!test)
  if (PIN_read(SIG_PIN))
	{
		RL3_sig_is_on = 0;
	}
}

void password_adjust_update(void)
{
	if (!button_active(DOWN) && !button_active(UP))
	{
		setting_increase = 0;
		adj_const = 1;
	}
	if (adjust_parameter(&password_para, 0, password_max_value, DOWN, UP, 0, adj_const))
	{
		if (++setting_increase == 20)
			adj_const = 10;
		else if (setting_increase == 40)
		{
			adj_const = 25;
		}
		display_3digit(password_para);
		password_blink_state = 1;
		ticks_reset(&password_blink_tick);
		lock_timeout_refresh();
	}
}

void password_input_display_init(uint8_t *label)
{
	password_blink_state = 1;
	ticks_reset(&password_blink_tick);
	display_3digit(password_para);
	display_string(OP, label);
}

void password_blink_update(void)
{
	if (tick_timeout(&password_blink_tick, 500))
	{
		password_blink_state ^= 1;
		if (password_blink_state)
		{
			display_3digit(password_para);
		}
		else
		{
			TM1650_write_n_bytes(&RED, 3, sMap(OFF), sMap(OFF), sMap(OFF));
		}
	}
}

void password_display_ok(void)
{
	display_string(OP, "don");
	TM1650_update(&GREEN);
	Clear_WDT();
	delay_ms(500);
	Clear_WDT();
}

void password_display_error(void)
{
	TM1650_write_n_bytes_fixed(&GREEN, 3, sMap(SUB), sMap(SUB), sMap(SUB));
	Clear_WDT();
	delay_ms(500);
	Clear_WDT();
}

void password_display_yes(void)
{
	display_3digit(password_para);
	display_string(OP, "yes");
	TM1650_update(&GREEN);
	TM1650_update(&RED);
	Clear_WDT();
	delay_ms(500);
	Clear_WDT();
}

void password_display_no(void)
{
	display_3digit(password_para);
	display_string(OP, "no");
	TM1650_update(&GREEN);
	TM1650_update(&RED);
	Clear_WDT();
	delay_ms(500);
	Clear_WDT();
}

void password_period_tick_reset(void)
{
	password_day_tick = 0;
}

void program_PASSWORD_CHECK(void)
{
	if (!eData.pin_enable)
	{
		password_gate = 0;
		password_change_enable = 0;
		switch_program(IDLE);
		return;
	}
	if (!program_init)
	{
		program_init = true;
		password_para = 0;
		password_wrong_count = 0;
		setting_increase = 0;
		adj_const = 1;
		password_input_display_init("PiN");
		display_set_enable(CL, 1);
		display_set_enable(OP, 1);
		lock = LOCK_OFF;
		lock_timeout_refresh();
	}
	lock = LOCK_OFF;
	lock_timeout_refresh();
	password_adjust_update();
	password_blink_update();
	if (button_released(SET))
	{
		lock_timeout_refresh();
		if (password_para == eData.pin_password)
		{
			password_wrong_count = 0;
			password_display_yes();
			if (password_gate)
			{
				password_gate = 0;
				eData.pin_day_count = 0;
				eData.pin_backup_used = 0;
				password_request_clear();
				password_period_tick_reset();
				password_change_enable = 0;
				AP_EEPROM_Commit();
				switch_program(IDLE);
			}
			else if (password_change_enable && !eData.pin_backup_used)
			{
				switch_program(PASSWORD_CHANGE);
			}
			else
			{
				password_change_enable = 0;
				switch_program(IDLE);
			}
		}
		else
		{
			password_wrong_count++;
			password_display_no();
			if (password_wrong_count >= eData.pin_wrong_limit)
			{
				if (password_change_enable)
				{
					password_wrong_count = 0;
					password_change_enable = 0;
					password_para = 0;
					button_reset(btnTotal);
					switch_program(IDLE);
					return;
				}
				if (eData.pin_backup_used)
				{
					password_wrong_count = 0;
					eData.pin_locked = 0;
					AP_EEPROM_Commit();
					password_para = 0;
					password_input_display_init("PiN");
					button_reset(btnTotal);
					return;
				}
				eData.pin_locked = 1;
				AP_EEPROM_Commit();
				switch_program(PASSWORD_BACKUP);
				return;
			}
			password_para = 0;
			password_input_display_init("PiN");
			button_reset(btnTotal);
		}
	}
}

void program_PASSWORD_CHANGE(void)
{
	if (!program_init)
	{
		if (!eData.pin_enable || !password_change_enable || eData.pin_backup_used)
		{
			password_change_enable = 0;
			switch_program(IDLE);
			return;
		}
		program_init = true;
		password_para = 0;
		setting_increase = 0;
		adj_const = 1;
		password_input_display_init("Pin");
		display_set_enable(CL, 1);
		display_set_enable(OP, 1);
		lock = LOCK_OFF;
		lock_timeout_refresh();
	}
	lock = LOCK_OFF;
	lock_timeout_refresh();
	password_adjust_update();
	password_blink_update();
	if (button_released(SET))
	{
		eData.pin_password = password_para;
		password_para = eData.pin_period_day;
		password_change_enable = 0;
		AP_EEPROM_Commit();
		password_display_ok();
		if (eData.pin_period_show)
		{
			switch_program(PASSWORD_TIME_SETTING);
		}
		else
		{
			switch_program(switch_state);
		}
	}
}

void program_PASSWORD_TIME_SETTING(void)
{
	if (!eData.pin_enable || !eData.pin_period_show)
	{
		password_change_enable = 0;
		switch_program(IDLE);
		return;
	}
	if (!program_init)
	{
		program_init = true;
		password_para = eData.pin_period_day;
		setting_increase = 0;
		adj_const = 1;
		password_input_display_init("LoP");
		display_set_enable(CL, 1);
		display_set_enable(OP, 1);
		lock = LOCK_OFF;
		lock_timeout_refresh();
	}
	lock = LOCK_OFF;
	lock_timeout_refresh();
	password_adjust_update();
	if (button_released(SET))
	{
		eData.pin_period_day = password_para;
		eData.pin_day_count = 0;
		password_request_clear();
		password_period_tick_reset();
		AP_EEPROM_Commit();
		switch_program(switch_state);
	}
}

void program_PASSWORD_BACKUP(void)
{
	if (!eData.pin_enable)
	{
		password_gate = 0;
		password_change_enable = 0;
		switch_program(IDLE);
		return;
	}
	if (!program_init)
	{
		program_init = true;
		password_para = 0;
		setting_increase = 0;
		adj_const = 1;
		password_input_display_init("bAc");
		display_set_enable(CL, 1);
		display_set_enable(OP, 1);
		lock = LOCK_OFF;
		lock_timeout_refresh();
	}
	lock = LOCK_OFF;
	lock_timeout_refresh();
	password_adjust_update();
	password_blink_update();
	if (button_released(SET))
	{
		lock_timeout_refresh();
		if (password_para == eData.pin_backup_password)
		{
			password_wrong_count = 0;
			password_gate = 0;
			password_change_enable = 0;
			eData.pin_locked = 0;
			eData.pin_backup_used = 1;
			eData.pin_day_count = 0;
			password_request_clear();
			password_period_tick_reset();
			AP_EEPROM_Commit();
			password_display_yes();
			switch_program(IDLE);
		}
		else
		{
			password_display_no();
			password_para = 0;
			password_input_display_init("bAc");
			button_reset(btnTotal);
		}
	}
}

void password_permanent_lock(void)
{
	default_state();
	display_set_enable(CL, 1);
	display_set_enable(OP, 1);
	display_string(CL, "Loc");
	display_string(OP, "---");
	TM1650_update(&RED);
	TM1650_update(&GREEN);
	while (1)
	{
		Clear_WDT();
		telemetry_update();
	}
}

uint32_t password_period_tick_max(void)
{
	return password_hour_tick_max;
}

void password_periodic_update(void)
{
	if (!eData.pin_enable || eData.pin_locked || (!eData.pin_backup_used && (eData.pin_period_day == 0 || eData.pin_request)))
	{
		password_period_tick_reset();
		return;
	}
	if (++password_day_tick >= password_period_tick_max())
	{
		password_period_tick_reset();
		eData.pin_day_count++;
		if (eData.pin_backup_used)
		{
			password_request_clear();
			if (eData.pin_day_count >= password_backup_target_count())
			{
				eData.pin_day_count = 0;
				eData.pin_locked = 1;
			}
		}
		else if (eData.pin_day_count >= password_request_target_count())
		{
			password_request_set();
		}
		AP_EEPROM_Commit();
	}
}

void program_IDLE(void)
{
	if (!program_init)
	{
		program_init = true;
		TM1650_string(&GREEN, "---");
		TM1650_string(&RED, "---");
		ticks_reset(&tick);
		setting_led_state = 1;
	}

	if (tick_timeout(&tick, 1000))
	{
		setting_led_state ^= 1;
		display_set_enable(CL, setting_led_state);
		display_set_enable(OP, setting_led_state);
	}

	if (switch_state == SW_COOL)
	{
		switch_program(COOL);
	}
	else if (switch_state == SW_ICE_FLUSH)
	{
		switch_program(ICE_FLUSH);
	}
}

void program_COOL(void)
{
	if (!program_init)
	{
		program_init = true;
		countdown_init();
		RL2_ST_event_update();
		//----- 250211 End
	
		DBG_NUM(event_ST_on);
		DBG_MSG(" - STON\n");

		DBG_NUM(event_ST_off);
		DBG_MSG(" - STOFF\n");

		display_set_enable(CL, 1);
		display_set_enable(OP, 1);
	}

	countdown_timer();
	open_time_t();
	switch (progress)
	{
	case OP:
		if (eData.HCF)
		{
//			if (test && !enable_hcf_OP_timeout_sig && !enable_open_time)
			if (!PIN_read(SIG_PIN) && !enable_hcf_OP_timeout_sig && !enable_open_time)
			{ // thay bang sig
				if (!hcf_mode)
				{
					hcf_mode = true;
					ticks_reset(&tick_hcf);
				}
				if (!mode_LED_on)
				{
					if (tick_timeout(&tick_hcf, 20000))
					{
						hcf_mode_timeout = 1;
						mode_END_disable = 1;
						ticks_reset(&tick_hcf);
					}
				}
				else
				{
					ticks_reset(&tick_hcf);
				}
					
			}
			else
			{
				if (hcf_mode)
				{
					hcf_mode = false;
					ticks_reset(&tick_hcf);
				}
				if ((!eData.DF || (eData.DF && progress_RL3 == CL)) && hcf_mode_timeout && !mode_LED_on && !enable_hcf_OP_timeout_sig && !open_time_is_sucess && !enable_open_time)
				{
					enable_hcf_OP_timeout_sig = 1;
					ticks_reset(&tick_hcf);
				}
				else
				{
					if (eData.DF && progress_RL3 == OP && countdown_RL3 <= 2 && hcf_mode_timeout && !enable_open_time && !mode_LED_on)
					{ // them !enable_hcf_OP_timeout_sig neu khach muon 15s mo rong khong bi ngat
						enable_open_time = 1;
						enable_hcf_OP_timeout_sig = 0; // xoa cai nay neu khach muon 15s mo rong khong bi ngat
						ticks_reset(&tick_hcf);		   // xoa cai nay neu khach muon 15s mo rong khong bi ngat
						mode_END_disable = 1;
					}
				}
			}
			if (enable_hcf_OP_timeout_sig && !enable_open_time)
			{ // bo open time neu khach muon 15s mo rong khong bi ngat
				if (!eData.DF)
				{
					if (tick_timeout(&tick_hcf, 15000))
					{
						hcf_mode_timeout = 0;
						mode_END_disable = 1;
						if (eData.mode == LCD)
						{
							countdown = 1;
						}
						else
						{
							switch (eData.on_time)
							{
							case 1:
								countdown = (ST_OFFSET * 2 + 10);
								break;
							case 0:
								countdown = ST_OFFSET;
								break;
							case 3:
								countdown = (ST_OFFSET * 2 + 10);
								break;
							case 2:
								countdown = ST_OFFSET;
								break;
							default:
								break;
							}
						}
						enable_hcf_OP_timeout_sig = 0;
						ticks_reset(&tick_hcf);
					}
				}
				else
				{
					if (progress_RL3 == CL)
					{
						if (tick_timeout(&tick_hcf, 15000))
						{
							hcf_mode_timeout = 0;
							mode_END_disable = 1;
							if (eData.mode == LCD)
							{
								countdown = 1;
							}
							else
							{
								switch (eData.on_time)
								{
								case 1:
									countdown = (ST_OFFSET * 2 + 10);
									break;
								case 0:
									countdown = ST_OFFSET;
									break;
								case 3:
									countdown = (ST_OFFSET * 2 + 10);
									break;
								case 2:
									countdown = ST_OFFSET;
									break;
								default:
									break;
								}
							}
							enable_hcf_OP_timeout_sig = 0;
							ticks_reset(&tick_hcf);
						}
					}
					else
					{
						ticks_reset(&tick_hcf);
					}
				}
			}
		}
		else
		{
			hcf_mode = false;
			hcf_mode_timeout = 0;
			enable_hcf_OP_timeout_sig = 0;
			enable_open_time = 0;
			mode_LED_on = 0;
			mode_END_disable = 0;
			ticks_reset(&tick_hcf);
		}

		switch (eData.mode)
		{
		case LCD:
			RL1_ON();
			RL2_ST_control(countdown < event_ST_on && countdown > event_ST_off);
			break;
		case LED:
			switch (eData.on_time)
			{
			case 0:
				if (0 < countdown && countdown <= ST_OFFSET)
				{
					RL1_ON();
					mode_LED_on = 1;
				}
				else
				{
					RL1_OFF();
					mode_LED_on = 0;
				}

				RL2_ST_LED_control(countdown <= event_ST_on && countdown > event_ST_off);
				break;

			case 1:
				if (0 < countdown && countdown <= ST_OFFSET || (countdown > (ST_OFFSET + 10) && countdown <= (ST_OFFSET * 2 + 10)))
				{
					RL1_ON();
					mode_LED_on = 1;
				}
				else
				{
					RL1_OFF();
					mode_LED_on = 0;
				}

				RL2_ST_LED_control(countdown <= event_ST_on && countdown > event_ST_off);
				break;

			case 2:
				if (0 < countdown && countdown <= ST_OFFSET)
				{
					RL1_ON();
					mode_LED_on = 1;
				}
				else
				{
					RL1_OFF();
					mode_LED_on = 0;
				}

				RL2_ST_LED_control((countdown <= event_ST_on && countdown > event_ST_off) || (countdown <= (event_ST_off - 10) && countdown > (event_ST_off - 10 - ST_OFFSET)));
				break;

			case 3:
				if (0 < countdown && countdown <= ST_OFFSET || (countdown > (ST_OFFSET + 10) && countdown <= (ST_OFFSET * 2 + 10)))
				{
					RL1_ON();
					mode_LED_on = 1;
				}
				else
				{
					RL1_OFF();
					mode_LED_on = 0;
				}
				RL2_ST_LED_control((countdown <= event_ST_on && countdown > event_ST_off) || (countdown <= (event_ST_off - 10) && countdown > (event_ST_off - 10 - ST_OFFSET)));
				break;
			default:
				break;
			}
			break;
		default:
			break;
		}
		if (0 < countdown && countdown <= 150 && eData.END && !hcf_mode_timeout && !mode_END_disable)
		{ // 250326 nhay RL3 15s neu END = ON
			RL3_ON();
		}
		else
		{
			if (eData.DF)
			{
				switch (progress_RL3)
				{ // 250326 chay RL3 binh theo OP2 CL2 binh thuong
				case OP:
					if(eData.first_progress2 == OP && !hcf_mode_timeout && eData.time_delay) {
						if(tick_timeout(&tick_delay_30s_RL3,100) && !is_delay) {
							if(--countdown_delay_RL3==0)
							{
								RL3_DF_ON();
								ticks_reset(&tick_delay_30s_RL3);
								countdown_delay_RL3=eData.time_delay*10;
								is_delay=1;
							}
						}
						else {
							if(!is_delay){								
								if (eData.check_box[ICE_FLUSH_OP_TIME] && progress_RL3 == OP) {
									countdown_RL3 = eData.time[ICE_FLUSH][progress_RL3];
									countdown_RL3 *= 10;
								}
								else {
									countdown_RL3 = eData.time[ICE_FLUSH][progress_RL3];
									countdown_RL3 *= 600;
								}
							}
							else
							{
								RL3_DF_ON();
							}
						}
					}
					else
					{
						RL3_DF_ON();
					}
					break;
				case CL:
					RL3_DF_OFF();
					break;
				default:
					break;
				}
			}
		}
		break;
	case CL:
		RL3_OFF();
		mode_END_disable = 0;
		if (eData.HCF)
		{
//			if (test && !enable_hcf_CL_timeout_sig && !enable_open_time)
			if (!PIN_read(SIG_PIN)   && !enable_hcf_CL_timeout_sig && !enable_open_time)
			{ 
				if (!hcf_mode)
				{
					hcf_mode = true;
					ticks_reset(&tick_hcf);
				}
				if (hcf_mode_timeout == 0)
				{
					if (tick_timeout(&tick_hcf, 20000))
					{
						hcf_mode_timeout = 1;
						enable_hcf_CL_timeout_sig = 0;
						if (eData.mode == LCD)
						{
							RL1_ON();
						}
						else
						{
							RL1_OFF();
						}
					}
					else
					{
						RL1_OFF();
					}
				}
			}
			else
			{
				if (hcf_mode)
				{
					hcf_mode = false;
					ticks_reset(&tick_hcf);
				}
				if ((!eData.DF || (eData.DF && progress_RL3 == CL)) && hcf_mode_timeout && !enable_hcf_CL_timeout_sig && !open_time_is_sucess && !enable_open_time)
				{
					enable_hcf_CL_timeout_sig = 1;
					ticks_reset(&tick_hcf);
				}
				else
				{
					if (eData.DF && progress_RL3 == OP && countdown_RL3 <= 2 && hcf_mode_timeout && !enable_open_time)
					{
						enable_open_time = 1;
						enable_hcf_CL_timeout_sig = 0;
						ticks_reset(&tick_hcf);
					}
				}
			}
			if (enable_hcf_CL_timeout_sig && !enable_open_time)
			{
				if (tick_timeout(&tick_hcf, 15000))
				{
					hcf_mode_timeout = 0;
					countdown = eData.time[COOL][CL]; // luon l� cool moi o day nen program de la COOL luon
					countdown *= 600;
					run_hcf_CL_timeout_sig = 1;
				}
			}
			else
			{
				if (!hcf_mode_timeout)
				{
					RL1_OFF();
					RL2_OFF();
				}
			}
			if (run_hcf_CL_timeout_sig)
			{
				a = (eData.time[COOL][CL] * 600) - ST_OFFSET;
				b = (eData.time[COOL][CL] * 600);
				switch (eData.mode)
				{
				case LCD:
					RL1_OFF();
					enable_hcf_CL_timeout_sig = 0;
					run_hcf_CL_timeout_sig = 0;
					ticks_reset(&tick_hcf);
					break;
				case LED:
					switch (eData.on_time)
					{
					case 0:
						if (a < countdown && countdown < b)
						{
							RL1_ON();
						}
						else
						{
							RL1_OFF();
						}
						if (countdown <= (eData.time[COOL][CL] * 600 - ST_OFFSET))
						{
							countdown = eData.time[COOL][CL] * 600;
							enable_hcf_CL_timeout_sig = 0;
							run_hcf_CL_timeout_sig = 0;
							ticks_reset(&tick_hcf);
						}
						break;
					case 1:
						if (((eData.time[COOL][CL] * 600 - ST_OFFSET) < countdown && countdown < (eData.time[COOL][CL] * 600)) || ((eData.time[COOL][CL] * 600 - 2 * ST_OFFSET - 10) < countdown && countdown < (eData.time[COOL][CL] * 600 - ST_OFFSET - 10)))
						{
							RL1_ON();
						}
						else
						{
							RL1_OFF();
						}
						if (countdown <= (eData.time[COOL][CL] * 600 - 2 * ST_OFFSET - 10))
						{
							countdown = eData.time[COOL][CL] * 600;
							enable_hcf_CL_timeout_sig = 0;
							run_hcf_CL_timeout_sig = 0;
							ticks_reset(&tick_hcf);
						}
						break;
					case 2:
						if ((eData.time[COOL][CL] * 600 - ST_OFFSET) < countdown && countdown < (eData.time[COOL][CL] * 600))
						{
							RL1_ON();
						}
						else
						{
							RL1_OFF();
						}
						if (countdown <= (eData.time[COOL][CL] * 600 - ST_OFFSET))
						{
							countdown = eData.time[COOL][CL] * 600;
							enable_hcf_CL_timeout_sig = 0;
							run_hcf_CL_timeout_sig = 0;
							ticks_reset(&tick_hcf);
						}
						break;
					case 3:
						if (((eData.time[COOL][CL] * 600 - ST_OFFSET) < countdown && countdown < (eData.time[COOL][CL] * 600)) || ((eData.time[COOL][CL] * 600 - 2 * ST_OFFSET - 10) < countdown && countdown < (eData.time[COOL][CL] * 600 - ST_OFFSET - 10)))
						{
							RL1_ON();
						}
						else
						{
							RL1_OFF();
						}
						if (countdown <= (eData.time[COOL][CL] * 600 - 2 * ST_OFFSET - 10))
						{
							countdown = eData.time[COOL][CL] * 600;
							enable_hcf_CL_timeout_sig = 0;
							run_hcf_CL_timeout_sig = 0;
							ticks_reset(&tick_hcf);
						}
						break;
					default:
						break;
					}
					break;
				default:
					break;
				}
			}
			if (eData.DF && hcf_mode_timeout)
			{
				switch (progress_RL3)
				{ // 250326 chay RL3 binh theo OP2 CL2 binh thuong
				case OP:						
					RL3_DF_ON();
				  
					break;
				case CL:
					RL3_DF_OFF();
					
					break;
				default:
					break;
				}
			}
		}
		else
		{			
			hcf_mode = false;
			hcf_mode_timeout = 0;
			enable_hcf_CL_timeout_sig = 0;
			ticks_reset(&tick_hcf);
			RL1_OFF();
			RL2_OFF();
		}
		break;
	default:
		break;
	}
	RL3_DF_reset_if_sig_idle();
	if (!eData.hDF)
	{
		if (switch_state == SW_IDLE)
		{
			switch_program(IDLE);
		}
		else if (switch_state == SW_ICE_FLUSH)
		{
			switch_program(ICE_FLUSH);
		}
	}
}
void program_ICE_FLUSH(void)
{
	if (eData.hDF)
	{
		program_COOL();
	}
	else
	{
		if (!program_init)
		{
			program_init = true;
			countdown_init();
			display_set_enable(CL, 1);
			display_set_enable(OP, 1);
		}

		countdown_timer();

		switch (progress)
		{
		case OP:
			RL3_ON();
			break;
		case CL:
			RL3_OFF();
			break;
		default:
			break;
		}

		if (switch_state == SW_IDLE)
		{
			switch_program(IDLE);
		}
		else if (switch_state == SW_COOL)
		{
			switch_program(COOL);
		}
	}
}

void program_SETTING(void)
{
	if (!eData.hDF)
	{
		if (!program_init)
		{
			program_init = true;
			if (switch_state == SW_COOL)
			{
				if (eData.HCF)
					display_num(OP, 0);
				else
					display_num(OP, eData.time[SET_MODE][OP]);
			}
			else if (switch_state == SW_ICE_FLUSH)
				display_num(OP, eData.time[SET_MODE][OP]);
			else if (switch_state == SW_IDLE)
			{
				if (eData.HCF)
					display_num(OP, 0);
				else
					display_num(OP, eData.time[SET_MODE][OP]);
			}
			display_num(CL, eData.time[SET_MODE][CL]);
			display_set_enable(CL, 1);
			display_set_enable(OP, 1);
			setting_timeout_refresh();
			ticks_reset(&setting_tick);
			setting_idx = CL;
			setting_led_state = 1;

			setting_increase = 0;
			adj_const = 1;
		}

		if (tick_timeout(&setting_tick, 500))
		{
			if (setting_timeout)
				--setting_timeout;
			setting_led_state ^= 1;
			display_set_enable(setting_idx, setting_led_state);
		}

		if (button_released(SET))
		{
			display_set_enable(setting_idx, 1);
			setting_idx ^= 1;
			if (switch_state == SW_COOL && eData.HCF)
			{
				if (setting_idx == OP)
				{
					setting_idx = CL;
				}
			}

			display_set_enable(setting_idx, 0);
			ticks_reset(&setting_tick);
			setting_led_state = 0;
			setting_timeout_refresh();
		}

		if (!button_active(DOWN) && !button_active(UP))
		{
			setting_increase = 0;
			adj_const = 1;
		}

		if (adjust_parameter(&eData.time[SET_MODE][setting_idx], eData.time_min[SET_MODE][setting_idx], eData.time_max[SET_MODE][setting_idx], DOWN, UP, 0, adj_const))
		{ //
			if (++setting_increase == 20)
				adj_const = 10;
			else if (setting_increase == 40)
			{
				adj_const = 25;
			}
			if (switch_state == SW_COOL && eData.HCF && setting_idx == OP)
			{
				display_num(OP, 0);
			}
			else
				display_num(setting_idx, eData.time[SET_MODE][setting_idx]);
			if (setting_led_state == 0)
			{
				setting_led_state = 1;
				display_set_enable(setting_idx, setting_led_state);
			}
			setting_timeout_refresh();
			ticks_reset(&setting_tick);
		}

		if (SET_MODE != switch_state % 2)
		{
			SET_MODE = switch_state % 2;
			program_init = false;
		}

		if (setting_timeout == 0)
		{
			display_set_enable(CL, 1);
			display_set_enable(OP, 1);
			switch_program(switch_state);
			AP_EEPROM_Commit();
		}
	}
	else
	{
		if (!program_init)
		{
			program_init = true;
			if (eData.HCF)
				display_num(OP, 0);
			else
				display_num(OP, eData.time[COOL][OP]);			
			display_num(CL, eData.time[COOL][CL]);
			display_set_enable(CL, 1);
			display_set_enable(OP, 1);
			setting_timeout_refresh();
			ticks_reset(&setting_tick);
			setting_idx = CL;
			setting_led_state = 1;

			setting_increase = 0;
			adj_const = 1;
		}

		if (tick_timeout(&setting_tick, 500))
		{
			if (setting_timeout)
				--setting_timeout;
			setting_led_state ^= 1;
			display_set_enable(setting_idx, setting_led_state);
		}

		if (button_released(SET))
		{
			display_set_enable(setting_idx, 1);
			setting_idx ^= 1;
			if (eData.HCF)
			{
				if (setting_idx == OP)
				{
					setting_idx = CL;
				}
			}

			display_set_enable(setting_idx, 0);
			ticks_reset(&setting_tick);
			setting_led_state = 0;
			setting_timeout_refresh();
		}

		if (!button_active(DOWN) && !button_active(UP))
		{
			setting_increase = 0;
			adj_const = 1;
		}

		if (adjust_parameter(&eData.time[COOL][setting_idx], eData.time_min[COOL][setting_idx], eData.time_max[COOL][setting_idx], DOWN, UP, 0, adj_const))
		{ //
			if (++setting_increase == 20)
				adj_const = 10;
			else if (setting_increase == 40)
			{
				adj_const = 25;
			}
			if (eData.HCF && setting_idx == OP)
			{
				display_num(OP, 0);
			}
			else
				display_num(setting_idx, eData.time[COOL][setting_idx]);
			if (setting_led_state == 0)
			{
				setting_led_state = 1;
				display_set_enable(setting_idx, setting_led_state);
			}
			setting_timeout_refresh();
			ticks_reset(&setting_tick);
		}
		if (setting_timeout == 0)
		{
			display_set_enable(CL, 1);
			display_set_enable(OP, 1);
			switch_program(switch_state);
			AP_EEPROM_Commit();
		}
	}
}

void display_adv_para(uint8_t idx)
{
	switch (idx)
	{
	case set_FirstProgress:
		if (switch_state == SW_ICE_FLUSH)
		{
			display_string(CL, "ON2");
			if (setting_para == OP)
			{
				display_string(OP, " OP");
			}
			else
			{
				display_string(OP, " CL");
			}
		}
		else
		{

			display_string(CL, "ON1");
			if (setting_para == OP)
			{
				display_string(OP, " OP");
			}
			else
			{
				display_string(OP, " CL");
			}
		}
		break;
	case set_ST:
		display_string(CL, " St");
		display_float(OP, setting_para);

		break;
	case set_DF:
		if (switch_state == SW_ICE_FLUSH)
		{
			display_string(CL, "hdF");
			if (setting_para == 1)
			{
				display_string(OP, " ON");
			}
			else
			{
				display_string(OP, "OFF");
			}
		}
		else
		{
			display_string(CL, "dEF");
			if (setting_para == 1)
			{
				display_string(OP, " ON");
			}
			else
			{
				display_string(OP, "OFF");
			}
		}
		break;
	case set_END:
		display_string(CL, "End");
		if (setting_para == 1)
		{
			display_string(OP, " ON");
		}
		else
		{
			display_string(OP, "OFF");
		}
		break;
	case set_Mode:
		display_string(CL, " SL");
		if (setting_para == LCD)
		{
			display_string(OP, "Lcd");
		}
		else
		{
			display_string(OP, "LED");
		}
		break;
	case set_HCF:
		display_string(CL, "hCF");
		if (setting_para == 1)
		{
			display_string(OP, "-CF");
		}
		else
		{
			display_string(OP, "h--");
		}
		break;
	default:
		break;
	}
	display_set_enable(OP, 1);
	display_set_enable(CL, 1);
}

void get_advance_setting_parameter(uint8_t idx)
{
	switch (idx)
	{
	case set_FirstProgress:
		if (switch_state == SW_ICE_FLUSH)
		{
			setting_para = eData.first_progress2;
		}
		else
		{
			setting_para = eData.first_progress1;
		}
		break;
	case set_ST:
		if(eData.mode == LCD)
		{
			setting_para = eData.ST_LCD;
		}
		else
		{
			setting_para = eData.ST_LED;
		}
		
		break;
	case set_DF:
		if (switch_state == SW_ICE_FLUSH)
		{
			setting_para = eData.hDF;
		}
		else
		{
			setting_para = eData.DF;
		}
		break;
	case set_END:
		setting_para = eData.END;
		break;
	case set_Mode:
		setting_para = eData.mode;
		break;
	case set_HCF:
		setting_para = eData.HCF;
		break;
	default:
		break;
	}
}

void save_adv_setting_parameter(uint8_t idx)
{
	switch (idx)
	{
	case set_FirstProgress:
		if (switch_state == SW_ICE_FLUSH)
		{
			eData.first_progress2 = setting_para;
			break;
		}
		else
		{
			eData.first_progress1 = setting_para;
			break;
		}
		break;
	case set_ST:
		if(eData.mode == LCD)
		{
			eData.ST_LCD = setting_para;
		}
		else
		{
			eData.ST_LED = setting_para;
		}
		break;
	case set_DF:
		if (switch_state == SW_ICE_FLUSH)
			eData.hDF = setting_para;
		else
			eData.DF = setting_para;
		break;
	case set_END:
		eData.END = setting_para;
		break;
	case set_Mode:
		eData.mode = setting_para;
		break;
	case set_HCF:
		eData.HCF = setting_para;
		break;
	default:
		break;
	}
}

void program_ADVANCE_SETTING(void)
{
	if (!program_init)
	{
		program_init = true;
		setting_timeout_refresh();
		ticks_reset(&setting_tick);
		setting_idx = 0;
		get_advance_setting_parameter(setting_idx);
		display_adv_para(setting_idx);
		setting_led_state = 1;
		setting_increase = 0;
		adj_const = 1;
		
		if (SET_MODE == 0)
			adv_total_menu = adv_settings_menu_total;
		else
		{
			adv_total_menu = 2; // only set OP
		}
	}

	if (tick_timeout(&setting_tick, 500))
	{
		if (setting_timeout)
			--setting_timeout;
		setting_led_state ^= 1;
		display_set_enable(OP, setting_led_state);
	}

	if (button_released(SET))
	{
		if(eData.mode == LCD)
		{
			adv_minmax[4][0] = eData.time_min[ST][LCD];
			adv_minmax[4][1] = eData.time_max[ST][LCD];
		}
		else
		{
			adv_minmax[4][0] = eData.time_min[ST][LED];
			adv_minmax[4][1] = eData.time_max[ST][LED];
		}
		if (adv_total_menu > 1)
		{
			save_adv_setting_parameter(setting_idx);
			if (++setting_idx >= adv_total_menu)
			{
				setting_idx = 0;
			}
			if (setting_idx == set_DF)
			{
				if(!SET_MODE)
				{
					if (!eData.check_box[HIDE_MODE_DF])
					{
						setting_idx++;
					}
				}
				else
				{
					if (!eData.check_box[HIDE_MODE_HDF])
					{
						setting_idx=0;
					}
				}
				
			}
			if (setting_idx == set_HCF)
			{
				if (!eData.check_box[HIDE_MODE_HCF])
				{
					setting_idx++;
				}
			}
			if (setting_idx == set_Mode)
			{
				if (!eData.check_box[HIDE_MODE_SL])
				{
					setting_idx++;
				}
			}
			if (setting_idx == set_ST)
			{
				if (!eData.hide_st)
				{
					setting_idx++;
				}
			}
			if (setting_idx == set_END)
			{
				if (!eData.check_box[HIDE_MODE_END])
				{
					setting_idx = 0;
				}
			}
			get_advance_setting_parameter(setting_idx);
			display_adv_para(setting_idx);
			ticks_reset(&setting_tick);
			setting_led_state = 1;
		}
		setting_timeout_refresh();
	}

	if (!button_active(DOWN) && !button_active(UP))
	{
		setting_increase = 0;
		adj_const = 1;
	}

	if (adjust_parameter(&setting_para, adv_minmax[setting_idx][MIN], adv_minmax[setting_idx][MAX], DOWN, UP, adv_rotate[setting_idx], adj_const))
	{
		if (adv_increase[setting_idx])
		{
			if (++setting_increase == 20)
				adj_const = 10;
			else if (setting_increase == 40)
			{
				adj_const = 20;
			}
		}
		display_adv_para(setting_idx);
		save_adv_setting_parameter(setting_idx);
		setting_led_state = 1;
		setting_timeout_refresh();
		ticks_reset(&setting_tick);
	}

	if (SET_MODE != switch_state % 2)
	{
		SET_MODE = switch_state % 2;
		get_advance_setting_parameter(setting_idx);
		save_adv_setting_parameter(setting_idx);
		program_init = false;
	}

	if (setting_timeout == 0)
	{
		display_set_enable(CL, 1);
		display_set_enable(OP, 1);
		switch_program(switch_state);
		save_adv_setting_parameter(setting_idx);
		AP_EEPROM_Commit();
	}
}

void display_history_data(void)
{
	uint16_t data_show;
	lock_timeout_refresh();

	TM1650_write_n_bytes_fixed(&RED, 3, num_hex[(list_data % 10)], num_hex[(list_data / 10)], sMap(L));
	data_show = eData.sig_count[list_data] / 600;

	if (data_show < 10)
	{
		TM1650_write_n_bytes_fixed(&GREEN, 3, num_hex[data_show % 10], sMap(OFF), sMap(OFF));
	}
	else if (10 <= data_show && data_show < 100)
	{
		TM1650_write_n_bytes_fixed(&GREEN, 3, num_hex[data_show % 10], num_hex[(data_show / 10) % 10], sMap(OFF));
	}
	else
	{
		TM1650_write_n_bytes_fixed(&GREEN, 3, num_hex[data_show % 10], num_hex[(data_show / 10) % 10], num_hex[data_show / 100]);
	}
	if (popup_timeout == 0)
	{
		show_his = 0;
	}
}

#define show_his_debounce_const 5
uint8_t show_his_up_state = 0;
uint8_t show_his_down_state = 0;
uint8_t show_his_up_count = 0;
uint8_t show_his_down_count = 0;

void show_his_button_reset(void)
{
	show_his_up_state = readButtonState(UP);
	show_his_down_state = readButtonState(DOWN);
	show_his_up_count = 0;
	show_his_down_count = 0;
}

bool show_his_button_pressed(uint8_t btn)
{
	bool raw_state;
	if (btn == UP)
	{
		raw_state = readButtonState(UP);
		if (raw_state != show_his_up_state)
		{
			if (++show_his_up_count >= show_his_debounce_const)
			{
				show_his_up_state = raw_state;
				show_his_up_count = 0;
				if (raw_state)
					return true;
			}
		}
		else
		{
			show_his_up_count = 0;
		}
	}
	else if (btn == DOWN)
	{
		raw_state = readButtonState(DOWN);
		if (raw_state != show_his_down_state)
		{
			if (++show_his_down_count >= show_his_debounce_const)
			{
				show_his_down_state = raw_state;
				show_his_down_count = 0;
				if (raw_state)
					return true;
			}
		}
		else
		{
			show_his_down_count = 0;
		}
	}
	return false;
}

void check_lock_and_setting(void)
{
	uint8_t rl1_was_on;
	if (show_his)
	{
		if (button_released(SET))
		{
			show_his = 0;
			list_data = 0;
			popup_timeout = 1;
			lock_timeout = 1;
			return;
		}
		button_reset(UP);
		button_reset(DOWN);
		if (show_his_button_pressed(DOWN))
		{
			list_data--;
			popup_timeout_refresh_4s();
			if (list_data < 0)
			{
				list_data = HIS_MAX-1;
			}
		}
		if (show_his_button_pressed(UP))
		{
			list_data++;
			popup_timeout_refresh_4s();
			if (list_data > (HIS_MAX-1))
			{
				list_data = 0;
			}
		}
		display_history_data();
		return;
	}
	if (lock == LOCK_ON)
	{
		if (button_released(UP) || button_released(DOWN) || button_released(SET))
		{
			TM1650_write_n_bytes_fixed(&RED, 3, sMap(C), sMap(O), sMap(L));
			TM1650_write_n_bytes_fixed(&GREEN, 3, sMap(SUB), sMap(SUB), sMap(SUB));
			popup_timeout_refresh();
			return;
		}
		if (button_hold(UP, 60))
		{ // 3s
			lock = LOCK_OFF;
			TM1650_write_n_bytes_fixed(&RED, 3, sMap(C), sMap(O), sMap(L));
			TM1650_write_n_bytes_fixed(&GREEN, 3, sMap(F), sMap(F), sMap(O));
			popup_timeout_refresh();
			lock_timeout_refresh();
			button_reset(btnTotal);
			return;
		}
	}
	else
	{
		if (program < SETTING)
		{
			if (eData.touch_num)
			{
				if (button_released(SET))
				{
					lock_timeout_refresh();
					if (show_his)
					{
						show_his = 0;
						list_data = 0;
						popup_timeout = 1;
						lock_timeout = 1;
						return;
					}
					else
					{
						if (SET_press_timeout == 0)
						{
							SET_press_timeout = 10;
						}
						else
						{
							SET_press_timeout = 0;
							SET_MODE = switch_state % 2;
							switch_program(SETTING);
							return;
						}
					}
				}
			}
			else
			{
				if (button_released(SET))
				{
					lock_timeout_refresh();
					if (show_his)
					{
						show_his = 0;
						list_data = 0;
						popup_timeout = 1;
						lock_timeout = 1;
						return;
					}
					else
					{
						SET_MODE = switch_state % 2;
						switch_program(SETTING);
						return;
					}
				}
			}
			if (button_released(DOWN))
			{
				lock_timeout_refresh();
				if (show_his)
				{
					list_data--;
					popup_timeout_refresh_4s();
					if (list_data < 0)
					{
						list_data = HIS_MAX-1;
					}
				}
				else
				{
					if (DOWN_press_timeout == 0)
					{
						DOWN_press_timeout = 10;
						show_mode = 0;
					}
					else
					{
						DOWN_press_timeout = 0;
						show_mode = 1;
						popup_timeout_refresh_4s();
					}
				}
			}
			if (!show_his && button_hold(SET, 60) && !button_hold(UP,30) && !button_hold(DOWN,30))
			{ // 3s
				SET_MODE = switch_state % 2;
				switch_program(ADVANCE_SETTING);
				return;
			}
			if((eData.check_box[HIDE_MODE_HDF] && eData.hDF) || switch_state != SW_ICE_FLUSH)
			{
				if (button_released(UP) && eData.HCF)
				{
					if (show_his)
					{
						list_data++;
						popup_timeout_refresh_4s();
						if (list_data > (HIS_MAX-1))
						{
							list_data = 0;
						}
					}
					lock_timeout_refresh();

					if (UP_press_timeout == 0)
					{
						UP_press_timeout = 10;
					}
					else
					{
						UP_press_timeout = 0;

						if (show_his == 0)
						{
							list_data = 0;
							show_his = 1;
							show_his_button_reset();
						}
						popup_timeout_refresh_4s();
					}
				}

				if (show_his)
				{
					display_history_data();
					return;
				}
				if (!show_his && button_hold(UP, 180) && button_active_total() == 1)
				{
					if (!eData.pin_enable || eData.pin_backup_used)
					{
						password_change_enable = 0;
						button_reset(btnTotal);
						return;
					}
					password_gate = 0;
					password_change_enable = 1;
					switch_program(PASSWORD_CHECK);
					button_reset(btnTotal);
					return;
				}
				if (!show_his && button_hold(UP, 60) && button_hold(SET,60) && !button_hold(DOWN,30))
				{ 
					TM1650_write_n_bytes_fixed(&RED, 3, sMap(t), sMap(S), sMap(OFF));
					eData.hide_st ^= 1;
					if (eData.hide_st)
					{
						TM1650_write_n_bytes_fixed(&GREEN, 3, sMap(N), sMap(O), sMap(OFF));
					}
					else
					{
						TM1650_write_n_bytes_fixed(&GREEN, 3, sMap(F), sMap(F), sMap(O));
					}
					AP_EEPROM_Commit();
					popup_timeout_refresh_4s();
					lock_timeout_refresh();
					button_reset(btnTotal);
				}
				if (!show_his && button_hold(DOWN, 60) && !button_hold(UP,30) && !button_hold(SET,30) && eData.check_box[HIDE_MODE_DF])
				{
					RL3_OFF();
					eData.DF ^= 1;  
					RL3_sig_is_on = 0;
					progress_RL3 = eData.first_progress2;
					if (eData.check_box[ICE_FLUSH_OP_TIME] && progress_RL3 == OP)
					{
						countdown_RL3 = eData.time[ICE_FLUSH][progress_RL3];
						countdown_RL3 *= 10;
					}
					else
					{
						countdown_RL3 = eData.time[ICE_FLUSH][progress_RL3];
						countdown_RL3 *= 600;
					}
					TM1650_write_n_bytes_fixed(&RED, 3, sMap(F), sMap(E), sMap(d));
					if (eData.DF)
					{
						countdown_delay_RL3=1;
						TM1650_write_n_bytes_fixed(&GREEN, 3, sMap(N), sMap(O), sMap(OFF));
					}
				else
					{
						TM1650_write_n_bytes_fixed(&GREEN, 3, sMap(F), sMap(F), sMap(O));
					}
					AP_EEPROM_Commit();
					popup_timeout_refresh_4s();
					lock_timeout_refresh();
					button_reset(btnTotal);
				}
				if (!show_his && button_hold(DOWN, 60) && button_hold(SET,60) && !button_hold(UP, 30) && eData.check_box[HIDE_MODE_SL])
				{
					TM1650_write_n_bytes_fixed(&RED, 3, sMap(L), sMap(S), sMap(OFF));
					eData.mode ^= 1;
					RL2_ST_event_update();
					RL2_LED_skip_on = 0;
					RL2_LED_had_on = 0;
					if (eData.mode)
					{
						TM1650_write_n_bytes_fixed(&GREEN, 3, sMap(D), sMap(E), sMap(L));
					}
					else
					{
						TM1650_write_n_bytes_fixed(&GREEN, 3, sMap(d), sMap(c), sMap(L));
					}
					AP_EEPROM_Commit();
					popup_timeout_refresh_4s();
					lock_timeout_refresh();
					button_reset(btnTotal);
				}
				
				if (!show_his && button_hold(DOWN, 60) && button_hold(UP,60) && !button_hold(SET,30) && eData.check_box[HIDE_MODE_HCF])
				{
					TM1650_write_n_bytes_fixed(&RED, 3, sMap(F), sMap(C), sMap(h));
					rl1_was_on = RL1_is_on;
					HCF_temp = eData.HCF^1;
				
					TM1650_number(&GREEN, eData.time[program][OP]);
					TM1650_number(&RED, eData.time[program][CL]);
					ticks_reset(&tick);
					
					if (!RL3_sig_is_on && !(eData.DF && progress_RL3 == CL))
					{
						countdown_delay_RL3=eData.time_delay*10;
					}
					if (switch_state == SW_COOL || (switch_state == SW_ICE_FLUSH && eData.hDF))
					{
						
						if (HCF_temp)
						{
							if (progress == OP )
							{
								countdown = eData.HCF_time;
								countdown *= 600;
							}
						}
						else
						{
							if (progress == OP)
							{
								countdown = eData.time[COOL][OP];
								countdown *= 600;
							}
							else
							{
								if(hcf_mode)
								{
									progress = OP;
									countdown = eData.time[COOL][OP];
									countdown *= 600;
								}
							}
						}
						// 250326 init RL3 time = time xa da
						if (!RL3_sig_is_on && !(eData.DF && progress_RL3 == CL))
						{
							progress_RL3 = eData.first_progress2;
							if (eData.check_box[ICE_FLUSH_OP_TIME] && progress_RL3 == OP)
							{
								countdown_RL3 = eData.time[ICE_FLUSH][progress_RL3];
								countdown_RL3 *= 10;
							}
							else
							{
								countdown_RL3 = eData.time[ICE_FLUSH][progress_RL3];
								countdown_RL3 *= 600;
							}
						}
					}
					else if (switch_state == SW_ICE_FLUSH && !eData.hDF)
					{
					
						if (eData.check_box[ICE_FLUSH_OP_TIME] && progress == OP)
						{
							countdown = eData.time[SW_ICE_FLUSH][OP];
							countdown *= 10;
						}
						else
						{
							if(progress == OP)
							{
								countdown = eData.time[SW_ICE_FLUSH][OP];
								countdown *= 600;
							}
						}
					}
					if (!RL3_sig_is_on && !(eData.DF && progress_RL3 == CL))
					{
						is_delay=0;
					}
					eData.HCF ^= 1;
					RL2_ST_event_update();
					if (rl1_was_on)
					{
						RL2_is_on = 1;
					}
					if (eData.mode == LED && (rl1_was_on || RL2_LED_had_on || hcf_mode || hcf_mode_timeout || enable_hcf_CL_timeout_sig || run_hcf_CL_timeout_sig))
					{
						RL2_LED_skip_on = 1;
					}
					if (eData.HCF == 1)
					{
						TM1650_write_n_bytes_fixed(&GREEN, 3, sMap(F), sMap(C), sMap(SUB));
					}
					else
					{
						TM1650_write_n_bytes_fixed(&GREEN, 3, sMap(SUB), sMap(SUB), sMap(h));
					}
					AP_EEPROM_Commit();
					popup_timeout_refresh_4s();
					lock_timeout_refresh();
					button_reset(btnTotal);
				}
			}
			if(switch_state == SW_ICE_FLUSH)
			{
				if (!show_his && button_hold(DOWN, 60) && button_hold(SET,60) && button_hold(UP,60) && eData.check_box[HIDE_MODE_HDF])
				{ // 3s khi bật lên hDF on là ẩn OPCL2 theo DF, off RL3 theo OPCL2
					RL1_OFF();
					RL2_OFF();
					RL3_OFF();
					eData.hDF ^= 1;
					program_init = false;
					hcf_mode   = false;
					hcf_mode_timeout = 0;
					RL3_sig_is_on = 0;
					enable_hcf_CL_timeout_sig = 0;
					run_hcf_CL_timeout_sig = 0;
					enable_hcf_OP_timeout_sig = 0;
					mode_LED_on = 0;
					mode_END_disable = 0;
					ticks_reset(&tick_hcf);
					countdown_init();
					TM1650_write_n_bytes_fixed(&RED, 3, sMap(F), sMap(d), sMap(h));
					if (eData.hDF)
					{
						countdown_delay_RL3=1;
						TM1650_write_n_bytes_fixed(&GREEN, 3, sMap(N), sMap(O), sMap(OFF));
					}
					else
					{
						TM1650_write_n_bytes_fixed(&GREEN, 3, sMap(F), sMap(F), sMap(O));
					}
					AP_EEPROM_Commit();
					popup_timeout_refresh_4s();
					lock_timeout_refresh();
					button_reset(btnTotal);
				}
			}
			if (show_mode && !show_his)
			{
				lock_timeout_refresh();
				if(switch_state == SW_ICE_FLUSH)
				{
					if (popup_timeout >= 20)
					{
						TM1650_write_n_bytes_fixed(&RED, 3, sMap(F), sMap(d), sMap(h));
						if (eData.hDF)
						{
							TM1650_write_n_bytes_fixed(&GREEN, 3, sMap(N), sMap(O), sMap(OFF));
						}
						else
						{
							TM1650_write_n_bytes_fixed(&GREEN, 3, sMap(F), sMap(F), sMap(O));
						}
					}
					if (popup_timeout == 0)
						show_mode = 0;
				}
				else
				{
					if (popup_timeout >= 20)
					{
						TM1650_write_n_bytes_fixed(&RED, 3, sMap(F), sMap(E), sMap(d));
						if (eData.DF)
						{
							TM1650_write_n_bytes_fixed(&GREEN, 3, sMap(N), sMap(O), sMap(OFF));
						}
						else
						{
							TM1650_write_n_bytes_fixed(&GREEN, 3, sMap(F), sMap(F), sMap(O));
						}
					}
					else
					{
						TM1650_write_n_bytes_fixed(&RED, 3, sMap(L), sMap(S), sMap(OFF));
						if (eData.mode == LCD)
						{
							TM1650_write_n_bytes_fixed(&GREEN, 3, sMap(d), sMap(c), sMap(L));
						}
						else
						{
							TM1650_write_n_bytes_fixed(&GREEN, 3, sMap(d), sMap(E), sMap(L));
						}
						if (popup_timeout == 0)
							show_mode = 0;
					}
				}
			}
		}
		else
		{
			lock_timeout_refresh();
		}
	}
}

void main()
{
	GPIO_INIT();
	default_state();
	MODIFY_HIRC_166();
	eeprom_init();
	password_day_tick = 0;
	delay_ms(100);
	shutdown_timeout_refresh();
	display_init();
	setFuncReadBtn(&readButtonState);
	button_init();
	Timer1_INIT();
	telemetry_init();
	EA = 1;
	On_WDT_1638_mS();
	ticks_reset(&sys_tick);
	DBG_INIT();
	ADC_INIT();
	delay_ms(500);
	/* 	
		if (button_active(UP) && button_active(DOWN) && button_active(SET)){
		TM1650_write_n_bytes(&GREEN, 3, sMap(r), sMap(E), sMap(V));
		TM1650_update(&GREEN);
		TM1650_number(&RED, version);
		TM1650_update(&RED);
		delay_ms(1000);}
	*/
	if (eData.pin_enable && eData.pin_locked && eData.pin_backup_used)
	{
		program = PASSWORD_BACKUP;
		password_permanent_lock();
	}
	else if (eData.pin_enable && eData.pin_locked)
	{
		password_gate = 1;
		password_change_enable = 0;
		switch_program(PASSWORD_BACKUP);
	}
	else if (eData.pin_enable && !eData.pin_backup_used && eData.pin_period_day && password_request_valid())
	{
		password_gate = 1;
		password_change_enable = 0;
		switch_program(PASSWORD_CHECK);
	}
	else
	{
		password_gate = 0;
		password_change_enable = 0;
		switch_program(IDLE);
	}
	while (1)
	{
		ADC_update();
		button_update();
		
		if (popup_timeout == 0)
		{
			TM1650_update(&GREEN);
			TM1650_update(&RED);
		}
		if (tick_timeout(&sys_tick, 100))
		{
			Clear_WDT();
			if (SET_press_timeout)
				--SET_press_timeout;
			if (DOWN_press_timeout)
				--DOWN_press_timeout;
			if (UP_press_timeout)
				--UP_press_timeout;
			if (popup_timeout)
				--popup_timeout;
			if (lock_timeout && eData.lock_time!=0)
			{
				if (--lock_timeout == 0)
				{
					lock = LOCK_ON;
					TM1650_write_n_bytes_fixed(&RED, 3, sMap(C), sMap(O), sMap(L));
					TM1650_write_n_bytes_fixed(&GREEN, 3, sMap(N), sMap(O), sMap(OFF));
					popup_timeout_refresh();
				}
			}
			if (!eData.shutdown && eData.try_time != 0)
			{
				if (eData.check_box[TRY_TIME_UNIT])
				{
					if (++shutdown_timeout >= 30*600) // 30phut/lan
					{ // 60 phut luu 1 lan
						shutdown_timeout=0;
						eData.tried_time++;
						if (eData.tried_time >= eData.try_time * 48)
						{ // 30 phut luu 1 lan -> 1 ngay luu 48 lan
							eData.shutdown = 1;
							eData.tried_time=0;
						}
						AP_EEPROM_Commit();
						shutdown_timeout_refresh();
					}
				}
				else
				{
					if (++shutdown_timeout >= 10*600) // 10phut/lan
					{ // 10 phut 1 lan
						shutdown_timeout=0;
						eData.tried_time++;
						if (eData.tried_time >= eData.try_time *6)
						{ // 5 phut luu 1 lan -> 1 gio luu 12 lan
							eData.shutdown = 1;
							eData.tried_time=0;
						}
						AP_EEPROM_Commit();
						shutdown_timeout_refresh();
					}
				}
			}
			password_periodic_update();
			if (eData.shutdown)
			{
				TM1650_write_n_bytes_fixed(&RED, 3, sMap(SUB), sMap(SUB), sMap(SUB));
				TM1650_write_n_bytes_fixed(&GREEN, 3, sMap(SUB), sMap(SUB), sMap(SUB));
				default_state();
			
				while (1)
				{
					Clear_WDT();
					telemetry_update();
				}
			}
		}
		switch (program)
		{
		case IDLE:
			program_IDLE();
			break;
		case COOL:
			program_COOL();
			break;
		case ICE_FLUSH:
			program_ICE_FLUSH();
			break;
		case SETTING:
			program_SETTING();
			break;
		case ADVANCE_SETTING:
			program_ADVANCE_SETTING();
			break;
		case PASSWORD_CHECK:
			program_PASSWORD_CHECK();
			break;
		case PASSWORD_CHANGE:
			program_PASSWORD_CHANGE();
			break;
		case PASSWORD_TIME_SETTING:
			program_PASSWORD_TIME_SETTING();
			break;
		case PASSWORD_BACKUP:
			program_PASSWORD_BACKUP();
			break;
		default:
			break;
		}
		check_lock_and_setting();
		telemetry_update();

#ifdef DEBUG
		if (countdown / 10 != c)
		{
			c = countdown / 10;
			DBG_MSG("prg ");
			DBG_NUM(progress);
			DBG_MSG(" | ");
			DBG_NUM(countdown / 10);
			DBG_MSG(" | ");
			DBG_NUM(RL1_output_is_on);
			DBG_MSG(" | ");
			DBG_NUM(RL2_output_is_on);
			DBG_MSG(" | ");
			DBG_NUM(RL3_output_is_on);
			DBG_MSG("\r\n");
		}
#endif
	}
}
