#ifndef DATA_FLASH_C_
#define DATA_FLASH_C_
#include "Data_Flash.h"

uint8_t *page_buffer;
uint16_t Size_Buffer   = 0;
uint16_t Address_Flash = 0;

uint8_t AP_EEPROM_Read(uint16_t code *u16_addr) {
	uint8_t rdata;
	rdata = *u16_addr >> 8;
	return rdata;
}

void AP_EEPROM_Init(uint16_t flash_total, uint16_t flash_data, uint8_t *buff, uint16_t size) {
	bool     bit_EA = false;
	uint8_t *ptr    = 0;
	uint16_t i      = 0;

	bit_EA = EA;
	EA     = 0;

	if (buff != No_Load) {
		ptr         = buff;
		page_buffer = buff;
	}
	else
		ptr = page_buffer;

	if (size != No_Load) Size_Buffer = size;
	if (flash_total != No_Load && flash_data != No_Load) Address_Flash = flash_total - flash_data;

	for (i = 0; i < Size_Buffer; i++, ptr++) {
		*ptr = AP_EEPROM_Read((uint16_t code *)(Address_Flash + i));
	}
	EA = bit_EA;
}

void AP_Erase_Page(uint16_t address, uint8_t page) {
	uint16_t addr = address;
	addr += page * 128;
	// Delete Page Flash
	IAPAL = addr & 0xff;
	IAPAH = (addr >> 8) & 0xff;
	IAPFD = 0xFF;
	TA_protected;
	CHPCON |= SET_BIT0;
	TA_protected;
	IAPUEN |= SET_BIT0;
	IAPCN = 0x22;
	TA_protected;
	IAPTRG |= SET_BIT0;
	// Delete Page Flash
}

void AP_EEPROM_Commit(void) {
	bool     bit_EA = false;
	uint8_t *ptr    = page_buffer;
	uint8_t  page   = Size_Buffer / 128;
	uint16_t i      = 0;

	bit_EA = EA;
	EA     = 0;

	if (Size_Buffer == 0) return;
	page++;
	for (i = 0; i < page; i++) {
		AP_Erase_Page(Address_Flash, i);
	}

	// Save Data To Page Flash
	TA_protected;
	CHPCON |= SET_BIT0;
	TA_protected;
	IAPUEN |= SET_BIT0;
	IAPCN = 0x21;
	for (i = 0; i < Size_Buffer; i++, ptr++) {
		IAPAL = ((Address_Flash + i) & 0xff);
		IAPAH = ((Address_Flash + i) >> 8) & 0xff;
		IAPFD = *ptr;
		TA_protected;
		IAPTRG |= SET_BIT0;
		TA_protected;
		WDCON |= SET_BIT6;
	}
	TA_protected;
	IAPUEN &= ~SET_BIT0;
	TA_protected;
	CHPCON &= ~SET_BIT0;
	// Save Data To Page Flash

	// Read Data Flash To XRAM
	AP_EEPROM_Init(No_Load, No_Load, No_Load, No_Load);
	EA = bit_EA;
}

#endif
