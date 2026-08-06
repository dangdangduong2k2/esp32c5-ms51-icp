#ifndef _DS1307_H_
#define _DS1307_H_

#define SEC            0x00
#define MIN            0x01
#define HOUR           0x02
#define DAY            0x03
#define DATE           0x04
#define MONTH          0x05
#define YEAR           0x06
#define DS1307_Control 0x07
#define DS1307_Ram     0x08

// #define __DS1307_READ_WRITE_ONE_BYTE_

typedef struct _Time {
	uint8_t sec;
	uint8_t min;
	uint8_t hour;
	uint8_t day;
	uint8_t date;
	uint8_t month;
	uint8_t year;
} DS1307_Time_str;

bool                  DS1307_Online = false;
DS1307_Time_str xdata DS1307_Time;

#ifdef __DS1307_READ_WRITE_ONE_BYTE_
uint8_t DS1307_read(uint8_t addr);
void    DS1307_Write(uint8_t addr, uint8_t dat);
#endif

bool    Check_DS1307_Online(void);
void    DS1307_Out_1Hz();
void    DS1307_Read_Data(uint8_t *ptr, uint8_t address, uint8_t length);
void    DS1307_Read_Time(DS1307_Time_str *_time);
void    DS1307_Write_Data(uint8_t *ptr, uint8_t address, uint8_t length);
void    DS1307_Write_Time(DS1307_Time_str *_time);
uint8_t BCD2HEX(uint8_t value);
uint8_t HEX2BCD(uint8_t value);
void    DS1307_Read_RAM(uint8_t *ptr, uint8_t address, uint8_t length);
void    DS1307_Write_RAM(uint8_t *ptr, uint8_t address, uint8_t length);

#endif
