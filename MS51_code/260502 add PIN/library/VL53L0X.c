#include "VL53L0X.h"

uint8_t readReg(uint8_t reg) {
	uint8_t value;
	I2C_SW_Start(&vl53l0x);
	I2C_SW_Write(&vl53l0x, address);
	I2C_SW_Write(&vl53l0x, reg);
	I2C_SW_Restart(&vl53l0x);
	I2C_SW_Write(&vl53l0x, address + 1);
	value = I2C_SW_Read(&vl53l0x, 0);
	I2C_SW_Stop(&vl53l0x);
	return value;
}

// Read a 16-bit register
uint16_t readReg16Bit(uint8_t reg) {
	uint16_t value;

	I2C_SW_Start(&vl53l0x);
	I2C_SW_Write(&vl53l0x, address);
	I2C_SW_Write(&vl53l0x, reg);

	I2C_SW_Restart(&vl53l0x);
	I2C_SW_Write(&vl53l0x, address + 1);
	value = I2C_SW_Read(&vl53l0x, 1);
	value <<= 8;
	value |= I2C_SW_Read(&vl53l0x, 0);
	I2C_SW_Stop(&vl53l0x);
	return value;
}

void readMulti(uint8_t reg, uint8_t *dst, uint8_t count) {
	I2C_SW_Start(&vl53l0x);
	I2C_SW_Write(&vl53l0x, address);
	I2C_SW_Write(&vl53l0x, reg);

	I2C_SW_Restart(&vl53l0x);
	I2C_SW_Write(&vl53l0x, address + 1);
	while (count-- > 0) {
		*(dst++) = I2C_SW_Read(&vl53l0x, 1);
	}
	I2C_SW_Stop(&vl53l0x);
}

void writeReg(uint8_t reg, uint8_t value) {
	I2C_SW_Start(&vl53l0x);
	I2C_SW_Write(&vl53l0x, address);
	I2C_SW_Write(&vl53l0x, reg);
	I2C_SW_Write(&vl53l0x, value);
	I2C_SW_Stop(&vl53l0x);
}

// Write a 16-bit register
void writeReg16Bit(uint8_t reg, uint16_t value) {
	I2C_SW_Start(&vl53l0x);
	I2C_SW_Write(&vl53l0x, address);
	I2C_SW_Write(&vl53l0x, reg);
	I2C_SW_Write(&vl53l0x, (uint8_t)value >> 8);
	I2C_SW_Write(&vl53l0x, (uint8_t)value);
	I2C_SW_Stop(&vl53l0x);
}

void writeMulti(uint8_t reg, uint8_t *src, uint8_t count) {
	I2C_SW_Start(&vl53l0x);
	I2C_SW_Write(&vl53l0x, address);
	I2C_SW_Write(&vl53l0x, reg);
	while (count-- > 0) {
		I2C_SW_Write(&vl53l0x, *(src++));
	}
	I2C_SW_Stop(&vl53l0x);
}

uint16_t readRangeContinuousMillimeters() {
	uint16_t range;
	startTimeout();
	while ((readReg(RESULT_INTERRUPT_STATUS) & 0x07) == 0) {
		if (checkTimeoutExpired()) {
			did_timeout = true;
			return 65535;
		}
	}

	// assumptions: Linearity Corrective Gain is 1000 (default);
	// fractional ranging is not enabled
	range = readReg16Bit(RESULT_RANGE_STATUS + 10);

	writeReg(SYSTEM_INTERRUPT_CLEAR, 0x01);

	return range;
}

uint16_t readRangeSingleMillimeters() {
	writeReg(0x80, 0x01);
	writeReg(0xFF, 0x01);
	writeReg(0x00, 0x00);
	writeReg(0x91, stop_variable);
	writeReg(0x00, 0x01);
	writeReg(0xFF, 0x00);
	writeReg(0x80, 0x00);

	writeReg(SYSRANGE_START, 0x01);

	// "Wait until start bit has been cleared"
	startTimeout();
	while (readReg(SYSRANGE_START) & 0x01) {
		if (checkTimeoutExpired()) {
			did_timeout = true;
			return 65535;
		}
	}

	return readRangeContinuousMillimeters();
}

void setTimeout(uint16_t timeout) { io_timeout = timeout; }

bool timeoutOccurred() {
	bool tmp    = did_timeout;
	did_timeout = false;
	return tmp;
}

bool setSignalRateLimit(float limit_Mcps) {
	if (limit_Mcps < 0 || limit_Mcps > 511.99) { return false; }

	// Q9.7 fixed point format (9 integer bits, 7 fractional bits)
	writeReg16Bit(FINAL_RANGE_CONFIG_MIN_COUNT_RATE_RTN_LIMIT, limit_Mcps * (1 << 7));
	return true;
}

// Get reference SPAD (single photon avalanche diode) count and type
// based on VL53L0X_get_info_from_device(),
// but only gets reference SPAD count and type
bool getSpadInfo(uint8_t *count, uint8_t *type_is_aperture) {
	uint8_t tmp;

	writeReg(0x80, 0x01);
	writeReg(0xFF, 0x01);
	writeReg(0x00, 0x00);

	writeReg(0xFF, 0x06);
	writeReg(0x83, readReg(0x83) | 0x04);
	writeReg(0xFF, 0x07);
	writeReg(0x81, 0x01);

	writeReg(0x80, 0x01);

	writeReg(0x94, 0x6b);
	writeReg(0x83, 0x00);
	startTimeout();
	while (readReg(0x83) == 0x00) {
		if (checkTimeoutExpired()) { return false; }
	}
	writeReg(0x83, 0x01);
	tmp = readReg(0x92);

	*count            = tmp & 0x7f;
	*type_is_aperture = (tmp >> 7) & 0x01;

	writeReg(0x81, 0x00);
	writeReg(0xFF, 0x06);
	writeReg(0x83, readReg(0x83) & ~0x04);
	writeReg(0xFF, 0x01);
	writeReg(0x00, 0x01);

	writeReg(0xFF, 0x00);
	writeReg(0x80, 0x00);

	return true;
}

void getSequenceStepEnables(SequenceStepEnables *enables) {
	uint8_t sequence_config = readReg(SYSTEM_SEQUENCE_CONFIG);
	enables->tcc            = (sequence_config >> 4) & 0x1;
	enables->dss            = (sequence_config >> 3) & 0x1;
	enables->msrc           = (sequence_config >> 2) & 0x1;
	enables->pre_range      = (sequence_config >> 6) & 0x1;
	enables->final_range    = (sequence_config >> 7) & 0x1;
}

uint32_t timeoutMclksToMicroseconds(uint16_t timeout_period_mclks, uint8_t vcsel_period_pclks) {
	uint32_t macro_period_ns = calcMacroPeriod(vcsel_period_pclks);

	return ((timeout_period_mclks * macro_period_ns) + 500) / 1000;
}

uint8_t getVcselPulsePeriod(uint8_t type) {
	if (type == VcselPeriodPreRange) { return decodeVcselPeriod(readReg(PRE_RANGE_CONFIG_VCSEL_PERIOD)); }
	else if (type == VcselPeriodFinalRange) { return decodeVcselPeriod(readReg(FINAL_RANGE_CONFIG_VCSEL_PERIOD)); }
	else { return 255; }
}

uint16_t decodeTimeout(uint16_t reg_val) {
	// format: "(LSByte * 2^MSByte) + 1"
	return (uint16_t)((reg_val & 0x00FF) << (uint16_t)((reg_val & 0xFF00) >> 8)) + 1;
}

void getSequenceStepTimeouts(SequenceStepEnables *enables, SequenceStepTimeouts *timeouts) {
	timeouts->pre_range_vcsel_period_pclks = getVcselPulsePeriod(VcselPeriodPreRange);

	timeouts->msrc_dss_tcc_mclks = readReg(MSRC_CONFIG_TIMEOUT_MACROP) + 1;
	timeouts->msrc_dss_tcc_us    = timeoutMclksToMicroseconds(timeouts->msrc_dss_tcc_mclks, timeouts->pre_range_vcsel_period_pclks);

	timeouts->pre_range_mclks = decodeTimeout(readReg16Bit(PRE_RANGE_CONFIG_TIMEOUT_MACROP_HI));
	timeouts->pre_range_us    = timeoutMclksToMicroseconds(timeouts->pre_range_mclks, timeouts->pre_range_vcsel_period_pclks);

	timeouts->final_range_vcsel_period_pclks = getVcselPulsePeriod(VcselPeriodFinalRange);

	timeouts->final_range_mclks = decodeTimeout(readReg16Bit(FINAL_RANGE_CONFIG_TIMEOUT_MACROP_HI));

	if (enables->pre_range) { timeouts->final_range_mclks -= timeouts->pre_range_mclks; }

	timeouts->final_range_us = timeoutMclksToMicroseconds(timeouts->final_range_mclks, timeouts->final_range_vcsel_period_pclks);
}

uint32_t getMeasurementTimingBudget() {
	uint32_t             budget_us;
	SequenceStepEnables  enables;
	SequenceStepTimeouts timeouts;

	// "Start and end overhead times always present"
	budget_us = StartOverhead + EndOverhead;

	getSequenceStepEnables(&enables);
	getSequenceStepTimeouts(&enables, &timeouts);

	if (enables.tcc) { budget_us += (timeouts.msrc_dss_tcc_us + TccOverhead); }

	if (enables.dss) { budget_us += 2 * (timeouts.msrc_dss_tcc_us + DssOverhead); }
	else if (enables.msrc) { budget_us += (timeouts.msrc_dss_tcc_us + MsrcOverhead); }

	if (enables.pre_range) { budget_us += (timeouts.pre_range_us + PreRangeOverhead); }

	if (enables.final_range) { budget_us += (timeouts.final_range_us + FinalRangeOverhead); }

	measurement_timing_budget_us = budget_us; // store for internal reuse
	return budget_us;
}

uint32_t timeoutMicrosecondsToMclks(uint32_t timeout_period_us, uint8_t vcsel_period_pclks) {
	uint32_t macro_period_ns = calcMacroPeriod(vcsel_period_pclks);

	return (((timeout_period_us * 1000) + (macro_period_ns / 2)) / macro_period_ns);
}

uint16_t encodeTimeout(uint32_t timeout_mclks) {
	// format: "(LSByte * 2^MSByte) + 1"

	uint32_t ls_byte = 0;
	uint16_t ms_byte = 0;

	if (timeout_mclks > 0) {
		ls_byte = timeout_mclks - 1;

		while ((ls_byte & 0xFFFFFF00) > 0) {
			ls_byte >>= 1;
			ms_byte++;
		}

		return (ms_byte << 8) | (ls_byte & 0xFF);
	}
	else { return 0; }
}

bool setMeasurementTimingBudget(uint32_t budget_us) {
	uint32_t             used_budget_us;
	uint32_t             final_range_timeout_us;
	uint32_t             final_range_timeout_mclks;
	SequenceStepEnables  enables;
	SequenceStepTimeouts timeouts;

	used_budget_us = StartOverhead + EndOverhead;

	getSequenceStepEnables(&enables);
	getSequenceStepTimeouts(&enables, &timeouts);

	if (enables.tcc) { used_budget_us += (timeouts.msrc_dss_tcc_us + TccOverhead); }

	if (enables.dss) { used_budget_us += 2 * (timeouts.msrc_dss_tcc_us + DssOverhead); }
	else if (enables.msrc) { used_budget_us += (timeouts.msrc_dss_tcc_us + MsrcOverhead); }

	if (enables.pre_range) { used_budget_us += (timeouts.pre_range_us + PreRangeOverhead); }

	if (enables.final_range) {
		used_budget_us += FinalRangeOverhead;

		// "Note that the final range timeout is determined by the timing
		// budget and the sum of all other timeouts within the sequence.
		// If there is no room for the final range timeout, then an error
		// will be set. Otherwise the remaining time will be applied to
		// the final range."

		if (used_budget_us > budget_us) {
			// "Requested timeout too big."
			return false;
		}

		final_range_timeout_us = budget_us - used_budget_us;

		// set_sequence_step_timeout() begin
		// (SequenceStepId == VL53L0X_SEQUENCESTEP_FINAL_RANGE)

		// "For the final range timeout, the pre-range timeout
		//  must be added. To do this both final and pre-range
		//  timeouts must be expressed in macro periods MClks
		//  because they have different vcsel periods."

		final_range_timeout_mclks = timeoutMicrosecondsToMclks(final_range_timeout_us, timeouts.final_range_vcsel_period_pclks);

		if (enables.pre_range) { final_range_timeout_mclks += timeouts.pre_range_mclks; }

		writeReg16Bit(FINAL_RANGE_CONFIG_TIMEOUT_MACROP_HI, encodeTimeout(final_range_timeout_mclks));

		// set_sequence_step_timeout() end

		measurement_timing_budget_us = budget_us; // store for internal reuse
	}
	return true;
}

// // based on VL53L0X_perform_single_ref_calibration()
bool performSingleRefCalibration(uint8_t vhv_init_byte) {
	writeReg(SYSRANGE_START, 0x01 | vhv_init_byte); // VL53L0X_REG_SYSRANGE_MODE_START_STOP

	startTimeout();
	while ((readReg(RESULT_INTERRUPT_STATUS) & 0x07) == 0) {
		if (checkTimeoutExpired()) { return false; }
	}

	writeReg(SYSTEM_INTERRUPT_CLEAR, 0x01);

	writeReg(SYSRANGE_START, 0x00);

	return true;
}

void VL53L0X_reset(void){
	Output_Pin(XSHUT_PIN, 0);
	delay_ms(1);
	Output_Pin(XSHUT_PIN, 1);
}

bool VL53L0X_init() {
	uint8_t spad_count;
	uint8_t spad_type_is_aperture;
	uint8_t ref_spad_map[6];
	uint8_t first_spad_to_enable;
	uint8_t spads_enabled = 0;
	uint8_t i;
	

	// check model ID register (value specified in datasheet)
	if (readReg(IDENTIFICATION_MODEL_ID) != 0xEE) {
		return false;
	}

	// VL53L0X_DataInit() begin

	// sensor uses 1V8 mode for I/O by default; switch to 2V8 mode if necessary
	// if (io_2v8) {
	writeReg(VHV_CONFIG_PAD_SCL_SDA__EXTSUP_HV, readReg(VHV_CONFIG_PAD_SCL_SDA__EXTSUP_HV) | 0x01); // set bit 0

	// "Set I2C standard mode"
	writeReg(0x88, 0x00);

	writeReg(0x80, 0x01);
	writeReg(0xFF, 0x01);
	writeReg(0x00, 0x00);
	stop_variable = readReg(0x91);
	writeReg(0x00, 0x01);
	writeReg(0xFF, 0x00);
	writeReg(0x80, 0x00);

	// disable SIGNAL_RATE_MSRC (bit 1) and SIGNAL_RATE_PRE_RANGE (bit 4) limit checks
	writeReg(MSRC_CONFIG_CONTROL, readReg(MSRC_CONFIG_CONTROL) | 0x12);

	// set final range signal rate limit to 0.25 MCPS (million counts per second)
	setSignalRateLimit(0.25);

	writeReg(SYSTEM_SEQUENCE_CONFIG, 0xFF);

	// VL53L0X_DataInit() end

	// VL53L0X_StaticInit() begin

	if (!getSpadInfo(&spad_count, &spad_type_is_aperture)) { return false; }

	// The SPAD map (RefGoodSpadMap) is read by VL53L0X_get_info_from_device() in
	// the API, but the same data seems to be more easily readable from
	// GLOBAL_CONFIG_SPAD_ENABLES_REF_0 through _6, so read it from there

	readMulti(GLOBAL_CONFIG_SPAD_ENABLES_REF_0, ref_spad_map, 6);

	// -- VL53L0X_set_reference_spads() begin (assume NVM values are valid)

	writeReg(0xFF, 0x01);
	writeReg(DYNAMIC_SPAD_REF_EN_START_OFFSET, 0x00);
	writeReg(DYNAMIC_SPAD_NUM_REQUESTED_REF_SPAD, 0x2C);
	writeReg(0xFF, 0x00);
	writeReg(GLOBAL_CONFIG_REF_EN_START_SELECT, 0xB4);

	first_spad_to_enable = spad_type_is_aperture ? 12 : 0; // 12 is the first aperture spad

	for (i = 0; i < 48; i++) {
		if (i < first_spad_to_enable || spads_enabled == spad_count) {
			// This bit is lower than the first one that should be enabled, or
			// (reference_spad_count) bits have already been enabled, so zero this bit
			ref_spad_map[i / 8] &= ~(1 << (i % 8));
		}
		else if ((ref_spad_map[i / 8] >> (i % 8)) & 0x1) { spads_enabled++; }
	}

	writeMulti(GLOBAL_CONFIG_SPAD_ENABLES_REF_0, ref_spad_map, 6);

	// -- VL53L0X_set_reference_spads() end

	// -- VL53L0X_load_tuning_settings() begin
	// DefaultTuningSettings from vl53l0x_tuning.h

	writeReg(0xFF, 0x01);
	writeReg(0x00, 0x00);

	writeReg(0xFF, 0x00);
	writeReg(0x09, 0x00);
	writeReg(0x10, 0x00);
	writeReg(0x11, 0x00);

	writeReg(0x24, 0x01);
	writeReg(0x25, 0xFF);
	writeReg(0x75, 0x00);

	writeReg(0xFF, 0x01);
	writeReg(0x4E, 0x2C);
	writeReg(0x48, 0x00);
	writeReg(0x30, 0x20);

	writeReg(0xFF, 0x00);
	writeReg(0x30, 0x09);
	writeReg(0x54, 0x00);
	writeReg(0x31, 0x04);
	writeReg(0x32, 0x03);
	writeReg(0x40, 0x83);
	writeReg(0x46, 0x25);
	writeReg(0x60, 0x00);
	writeReg(0x27, 0x00);
	writeReg(0x50, 0x06);
	writeReg(0x51, 0x00);
	writeReg(0x52, 0x96);
	writeReg(0x56, 0x08);
	writeReg(0x57, 0x30);
	writeReg(0x61, 0x00);
	writeReg(0x62, 0x00);
	writeReg(0x64, 0x00);
	writeReg(0x65, 0x00);
	writeReg(0x66, 0xA0);

	writeReg(0xFF, 0x01);
	writeReg(0x22, 0x32);
	writeReg(0x47, 0x14);
	writeReg(0x49, 0xFF);
	writeReg(0x4A, 0x00);

	writeReg(0xFF, 0x00);
	writeReg(0x7A, 0x0A);
	writeReg(0x7B, 0x00);
	writeReg(0x78, 0x21);

	writeReg(0xFF, 0x01);
	writeReg(0x23, 0x34);
	writeReg(0x42, 0x00);
	writeReg(0x44, 0xFF);
	writeReg(0x45, 0x26);
	writeReg(0x46, 0x05);
	writeReg(0x40, 0x40);
	writeReg(0x0E, 0x06);
	writeReg(0x20, 0x1A);
	writeReg(0x43, 0x40);

	writeReg(0xFF, 0x00);
	writeReg(0x34, 0x03);
	writeReg(0x35, 0x44);

	writeReg(0xFF, 0x01);
	writeReg(0x31, 0x04);
	writeReg(0x4B, 0x09);
	writeReg(0x4C, 0x05);
	writeReg(0x4D, 0x04);

	writeReg(0xFF, 0x00);
	writeReg(0x44, 0x00);
	writeReg(0x45, 0x20);
	writeReg(0x47, 0x08);
	writeReg(0x48, 0x28);
	writeReg(0x67, 0x00);
	writeReg(0x70, 0x04);
	writeReg(0x71, 0x01);
	writeReg(0x72, 0xFE);
	writeReg(0x76, 0x00);
	writeReg(0x77, 0x00);

	writeReg(0xFF, 0x01);
	writeReg(0x0D, 0x01);

	writeReg(0xFF, 0x00);
	writeReg(0x80, 0x01);
	writeReg(0x01, 0xF8);

	writeReg(0xFF, 0x01);
	writeReg(0x8E, 0x01);
	writeReg(0x00, 0x01);
	writeReg(0xFF, 0x00);
	writeReg(0x80, 0x00);

	// -- VL53L0X_load_tuning_settings() end

	// "Set interrupt config to new sample ready"
	// -- VL53L0X_SetGpioConfig() begin

	writeReg(SYSTEM_INTERRUPT_CONFIG_GPIO, 0x04);
	writeReg(GPIO_HV_MUX_ACTIVE_HIGH, readReg(GPIO_HV_MUX_ACTIVE_HIGH) & ~0x10); // active low
	writeReg(SYSTEM_INTERRUPT_CLEAR, 0x01);

	// -- VL53L0X_SetGpioConfig() end

	measurement_timing_budget_us = getMeasurementTimingBudget();

	// "Disable MSRC and TCC by default"
	// MSRC = Minimum Signal Rate Check
	// TCC = Target CentreCheck
	// -- VL53L0X_SetSequenceStepEnable() begin

	writeReg(SYSTEM_SEQUENCE_CONFIG, 0xE8);

	// -- VL53L0X_SetSequenceStepEnable() end

	// "Recalculate timing budget"
	setMeasurementTimingBudget(measurement_timing_budget_us);

	// VL53L0X_StaticInit() end

	// VL53L0X_PerformRefCalibration() begin (VL53L0X_perform_ref_calibration())

	// -- VL53L0X_perform_vhv_calibration() begin

	writeReg(SYSTEM_SEQUENCE_CONFIG, 0x01);
	if (!performSingleRefCalibration(0x40)) { return false; }

	// -- VL53L0X_perform_vhv_calibration() end

	// -- VL53L0X_perform_phase_calibration() begin

	writeReg(SYSTEM_SEQUENCE_CONFIG, 0x02);
	if (!performSingleRefCalibration(0x00)) { return false; }

	// -- VL53L0X_perform_phase_calibration() end

	// "restore the previous Sequence Config"
	writeReg(SYSTEM_SEQUENCE_CONFIG, 0xE8);

	// VL53L0X_PerformRefCalibration() end */

	return true;
}