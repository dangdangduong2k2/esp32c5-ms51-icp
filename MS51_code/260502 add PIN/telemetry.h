#ifndef _MS51_TELEMETRY_H_
#define _MS51_TELEMETRY_H_

/*
 * Runtime telemetry/configuration UART to ESP32-S3.  The existing ICP wires
 * are multiplexed only while the application runs: P1.6 / ICE_DAT is UART1
 * TXD and P0.2 / ICE_CLK is UART1 RXD.  The ESP32 releases both pins before
 * every ICP session.
 */
void telemetry_init(void);
void telemetry_update(void);

#endif
