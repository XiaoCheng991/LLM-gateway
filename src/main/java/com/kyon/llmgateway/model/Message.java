package com.kyon.llmgateway.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class Message {

    public String role;

    public String content;
}
