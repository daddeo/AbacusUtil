/*
 * Generated by Abacus.
 */
package com.landawn.samples.abacus.entity;

import java.sql.Timestamp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Device {
    private long id;
    private long accountId;
    private String name;
    private String udid;
    private String platform;
    private String model;
    private String manufacturer;
    private Timestamp produceTime;
    private String category;
    private String description;
    private int status;
    private Timestamp lastUpdateTime;
    private Timestamp createTime;
}
