package com.yulore.asrproxy.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.ToString;

@AllArgsConstructor
@Data
@ToString
public class PayloadSentenceBegin {
    int index;
    int time;
}
