#ifndef FUNCTION_SYSTEM_H_
#define FUNCTION_SYSTEM_H_

#ifndef _TA_
#define _TA_
#define TA_protected                                                                                                                                 \
	TA = 0xAA;                                                                                                                                       \
	TA = 0x55
#endif

void memset_User(uint8_t *destination, uint8_t value, uint8_t len);
void memcpy_User(uint8_t *destination, uint8_t *source, uint8_t len);
void reboot_system_AP_ROM(void);

#endif
