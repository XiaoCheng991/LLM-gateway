package com.kyon.llmgateway.eval.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CategoryStat {
    private int passed;
    private int total;
    private double passRate;
}
