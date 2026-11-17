package com.sayohno.marketing.coupon.service;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

@Data
public class BatchUserRow {
    @ExcelProperty("用户ID")
    private String userId;
}
