#ifndef DATA_FLASH_H_
#define DATA_FLASH_H_
#include "MS51_32K.h"

#define Flash_Total_12KB 0x3000 // Flash Size 12KB
#define Flash_Total_13KB 0x3400 // Flash Size 13KB
#define Flash_Total_14KB 0x3800 // Flash Size 14KB
#define Flash_Total_15KB 0x3C00 // Flash Size 15KB
#define Flash_Total_16KB 0x4000 // Flash Size 16KB
#define Flash_Total_17KB 0x4400 // Flash Size 17KB
#define Flash_Total_18KB 0x4800 // Flash Size 18KB
#define Flash_Total_28KB 0x7000 // 28KB APROM when 4KB LDROM is enabled
#define Flash_Total_32KB 0x8000 // Flash Size 32KB
#define Flash_Data_128B  0x0080
#define Flash_Data_256B  0x0100
#define Flash_Data_384B  0x0180
#define Flash_Data_512B  0x0200
#define Flash_Data_640B  0x0280
#define Flash_Data_768B  0x0300

#define No_Load 0x00

uint8_t AP_EEPROM_Read(uint16_t code *u16_addr);
void    AP_EEPROM_Init(uint16_t flash_total, uint16_t flash_data, uint8_t *buff, uint16_t size);
void    AP_Erase_Page(uint16_t address, uint8_t page);
void    AP_EEPROM_Commit(void);

#endif
