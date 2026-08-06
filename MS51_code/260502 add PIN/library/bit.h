/* Bit header file
    Version: 1.0
    Date modified: 240917
    Modified by: Tran-Luyen
    Source: https://github.com/Tran-Luyen/Code-Lib/blob/main/Common-lib/bit.h
    Github: https://github.com/Tran-Luyen
*/

#ifndef _BIT_H_
#define _BIT_H_

#define bitRead(value, bit)            (((value) >> (bit)) & 0x01)
#define bitToggle(value, bit)          ((value) ^= (1UL << (bit)))
#define bitSet(value, bit)             ((value) |= (1UL << (bit)))
#define bitClear(value, bit)           ((value) &= ~(1UL << (bit)))
#define bitWrite(value, bit, bitvalue) (bitvalue ? bitSet(value, bit) : bitClear(value, bit))

// VAL |= SET_BITx
#define SET_BIT0 0x01
#define SET_BIT1 0x02
#define SET_BIT2 0x04
#define SET_BIT3 0x08
#define SET_BIT4 0x10
#define SET_BIT5 0x20
#define SET_BIT6 0x40
#define SET_BIT7 0x80

// VAL &= CLR_BITx
#define CLR_BIT0 0xFE
#define CLR_BIT1 0xFD
#define CLR_BIT2 0xFB
#define CLR_BIT3 0xF7
#define CLR_BIT4 0xEF
#define CLR_BIT5 0xDF
#define CLR_BIT6 0xBF
#define CLR_BIT7 0x7F

#endif
