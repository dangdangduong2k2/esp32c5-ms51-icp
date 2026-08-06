/*
    Common header file
    Version: 1.2
    Date modified: 241218
    Author: Tran-Luyen
    Github: https://github.com/Tran-Luyen
    SOURCE: https://github.com/Tran-Luyen/Code-Lib/tree/main/Common-lib/seg_map.h
    File path: file:///D:\Works\Github_Projects\Code-Lib\Common-lib/seg_map.h
*/

#ifndef _SEG_MAP_H_
#define _SEG_MAP_H_

// cathode
#define sCC_0 0x3F
#define sCC_1 0x06
#define sCC_2 0x5B
#define sCC_3 0x4F
#define sCC_4 0x66
#define sCC_5 0x6D
#define sCC_6 0x7D
#define sCC_7 0x07
#define sCC_8 0x7F
#define sCC_9 0x6F

#define sCC_10 0x77
#define sCC_11 0x7C
#define sCC_12 0x39
#define sCC_13 0x5E
#define sCC_14 0x79
#define sCC_15 0x71

#define sCC_A 0x77
#define sCC_B 0x7C
#define sCC_C 0x39
#define sCC_D 0x5E
#define sCC_E 0x79
#define sCC_F 0x71

#define sCC_a 0x88
#define sCC_b 0x83
#define sCC_c 0x58
#define sCC_d sCC_D
#define sCC_f sCC_F
#define sCC_g sCC_9
#define sCC_G sCC_6
#define sCC_h 0x74
#define sCC_H 0x76
#define sCC_i 0x30
#define sCC_I sCC_i
#define sCC_l 0x30
#define sCC_L 0x38
#define sCC_n 0x54
#define sCC_N 0x37
#define sCC_o 0x5C
#define sCC_O sCC_0
#define sCC_p 0x73
#define sCC_P sCC_p
#define sCC_r 0x50
#define sCC_R sCC_A
#define sCC_S sCC_5
#define sCC_t 0x78
#define sCC_T 0x31
#define sCC_U 0x3E
#define sCC_V sCC_U
#define sCC_y 0x6E
#define sCC_Y sCC_y

#define sCC_ON  0xff
#define sCC_OFF 0x00
#define sCC_DOT 0x80
#define sCC_SUB 0x40 // -

// anode
#define sCA_0 0xC0
#define sCA_1 0xF9
#define sCA_2 0xA4
#define sCA_3 0xB0
#define sCA_4 0x99
#define sCA_5 0x92
#define sCA_6 0x82
#define sCA_7 0xF8
#define sCA_8 0x80
#define sCA_9 0x90

#define sCA_10 0x88
#define sCA_11 0x83
#define sCA_12 0xC6
#define sCA_13 0xA1
#define sCA_14 0x86
#define sCA_15 0x8E

#define sCA_A 0x88
#define sCA_B 0x83
#define sCA_C 0xC6
#define sCA_D 0xA1
#define sCA_E 0x86
#define sCA_F 0x8E

#define sCA_a sCA_A
#define sCA_b sCA_B
#define sCA_c 0xA7
#define sCA_d sCA_D
#define sCA_f sCA_F
#define sCA_g sCA_9
#define sCA_G sCA_6
#define sCA_h 0x8B
#define sCA_H 0x89
#define sCA_i 0xCF
#define sCA_I sCA_i
#define sCA_l 0xCF
#define sCA_L 0xC7
#define sCA_n 0xAB
#define sCA_N 0xC8
#define sCA_o 0xC0
#define sCA_O sCA_0
#define sCA_p 0x8C
#define sCA_P sCA_p
#define sCA_r 0xAF
#define sCA_R sCA_A
#define sCA_S sCA_5
#define sCA_t 0x87
#define sCA_T 0xCE
#define sCA_U 0xC1
#define sCA_V sCA_U
#define sCA_y 0x91
#define sCA_Y sCA_y

#define sCA_ON  0x00
#define sCA_OFF 0xff
#define sCA_DOT 0x7F
#define sCA_SUB 0xBF // -

#define sMap(C) sCC_##C // anode
// #define sMap(C) sCC_##C // cathode
// #define s_OFF 0x00
// #define sMap(ON) 0xff

// common cathode
const uint8_t seg_map[16] = {sMap(0), sMap(1), sMap(2), sMap(3), sMap(4), sMap(5), sMap(6), sMap(7), sMap(8), sMap(9), sMap(A), sMap(B), sMap(C), sMap(D), sMap(E), sMap(F)};

uint8_t seg_chr(uint8_t c) {
	if (c >= '0' && c <= '9') return seg_map[c - '0'];
	switch (c) {
		case ' ': return sMap(OFF); break;
		case '-': return sMap(SUB); break;
		case 'a':
		case 'A': return sMap(A); break;
		case 'b':
		case 'B': return sMap(B); break;
		case 'c': return sMap(c); break;
		case 'C': return sMap(C); break;
		case 'd':
		case 'D': return sMap(D); break;
		case 'e':
		case 'E': return sMap(E); break;
		case 'f':
		case 'F': return sMap(F); break;
		case 'g': return sMap(g); break;
		case 'h': return sMap(h); break;
		case 'i': return sMap(i); break;
		case 'I': return sMap(I); break;
		case 'l': return sMap(l); break;
		case 'L': return sMap(L); break;
		case 'n': return sMap(n); break;
		case 'N': return sMap(N); break;
		case 'o': return sMap(o); break;
		case 'O': return sMap(O); break;
		case 'p': return sMap(p); break;
		case 'P': return sMap(P); break;
		case 'r': return sMap(r); break;
		case 'R': return sMap(R); break;
		case 't': return sMap(t); break;
		case 'T': return sMap(T); break;
		case 's':
		case 'S': return sMap(S); break;
		case 'V': return sMap(V); break;
		case 'y':
		case 'Y': return sMap(y); break;
		default: return sMap(OFF); break;
	}
}

#endif
