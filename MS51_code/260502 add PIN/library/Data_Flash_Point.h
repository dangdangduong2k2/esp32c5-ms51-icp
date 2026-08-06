#ifndef DATA_FLASH_Point_H_
#define DATA_FLASH_Point_H_
// #define Address_EEPROM 0x2F00 // Flash Size 12KB - 256 Byte EEPROM by Flash
#define Address_EEPROM 0x3700 // Flash Size 14KB - 256 Byte EEPROM by Flash
// #define Address_EEPROM 0x3F00 // Flash Size 16KB - 256 Byte EEPROM by Flash
// #define Address_EEPROM 0x4700 // Flash Size 18KB - 256 Byte EEPROM by Flash

#ifndef _TA_
#define _TA_
#define TA_protected                                                                                                                                 \
	TA = 0xAA;                                                                                                                                       \
	TA = 0x55
#endif

uint8_t *page_buffer;
uint16_t Size_Buffer = 0;

uint8_t AP_EEPROM_Read(uint16_t code *u16_addr);
void    AP_EEPROM_Init(uint8_t *buff, uint16_t size);
void    AP_Erase_Page(uint16_t address, uint8_t page);
void    AP_EEPROM_Commit(void);

#endif
