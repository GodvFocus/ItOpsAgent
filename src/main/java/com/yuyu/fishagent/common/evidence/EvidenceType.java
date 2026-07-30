package com.yuyu.fishagent.common.evidence;

/**
 * 统一证据来源类型。
 */
public enum EvidenceType {

    /** RAG 召回命中。 */
    RAG,

    /** 日志检索命中。 */
    LOG,

    /** 服务状态快照。 */
    STATUS_SNAPSHOT,

    /** 工单或类似记录。 */
    TICKET,

    /** 无法进一步拆分的工具结果。 */
    TOOL_RESULT
}
