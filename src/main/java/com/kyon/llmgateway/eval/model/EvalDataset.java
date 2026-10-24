package com.kyon.llmgateway.eval.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvalDataset {
    private String name;
    private String version;
    private List<EvalCase> cases;
}
