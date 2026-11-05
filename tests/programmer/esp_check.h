#pragma once
#define ESP_RETURN_ON_ERROR(expr, tag, ...) do { int e_ = (expr); if (e_ != ESP_OK) return e_; } while (0)
#define ESP_RETURN_ON_FALSE(cond, err, tag, ...) do { if (!(cond)) return (err); } while (0)
