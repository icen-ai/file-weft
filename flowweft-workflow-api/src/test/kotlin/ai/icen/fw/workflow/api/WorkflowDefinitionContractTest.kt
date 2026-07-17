package ai.icen.fw.workflow.api

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class WorkflowDefinitionContractTest {
    @Test
    fun `builds a versioned Chinese human approval definition with explicit outcomes`() {
        val policyRules = mutableListOf(
            WorkflowHumanTaskParticipantRule.of(
                WorkflowParticipantSelector.group("法务审批组"),
                WorkflowApprovalPolicy.quorum(2),
            ),
        )
        val stages = mutableListOf(
            WorkflowParticipantResolutionStage.ACTIVATION,
            WorkflowParticipantResolutionStage.CLAIM,
            WorkflowParticipantResolutionStage.DECISION,
        )
        val policy = WorkflowHumanTaskPolicy.of(
            policyRules,
            WorkflowHumanTaskCapabilities.of(
                addSignEnabled = true,
                delegationEnabled = true,
                transferEnabled = false,
                claimEnabled = true,
            ),
            WorkflowSeparationOfDutiesPolicy.of(
                initiatorExcluded = true,
                priorApproversExcluded = true,
            ),
            stages,
        )
        policyRules.clear()
        stages.clear()

        val definition = approvalDefinition(policy = policy)

        assertEquals("租户-天津", definition.tenantId)
        assertEquals("法律文件审批", definition.definitionId)
        assertEquals("legal-document-approval", definition.key)
        assertEquals("2026.07.1", definition.version)
        assertEquals(1, definition.schemaVersion)
        assertSame(WorkflowDefinitionStatus.DRAFT, definition.status)
        assertEquals(definition.contentDigest, definition.ref.digest)
        assertEquals(definition.key, definition.ref.key)
        assertEquals(definition.version, definition.ref.version)
        assertEquals(64, definition.contentDigest.length)
        assertEquals(1, policy.participantRules.size)
        assertEquals(3, policy.resolutionStages.size)
        assertTrue(policy.capabilities.addSignEnabled)
        assertTrue(policy.capabilities.delegationEnabled)
        assertFalse(policy.capabilities.transferEnabled)
        assertTrue(policy.capabilities.claimEnabled)
        assertTrue(policy.separationOfDuties.initiatorExcluded)
        assertTrue(policy.separationOfDuties.priorApproversExcluded)
        assertFailsWith<UnsupportedOperationException> {
            (definition.nodes as MutableList<WorkflowNodeDefinition>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (policy.resolutionStages as MutableList<WorkflowParticipantResolutionStage>).clear()
        }
        assertEquals("WorkflowDefinition(<redacted>)", definition.toString())
    }

    @Test
    fun `content digest is canonical ordered content and excludes lifecycle declaration`() {
        val draft = approvalDefinition(status = WorkflowDefinitionStatus.DRAFT)
        val publishedClaim = approvalDefinition(status = WorkflowDefinitionStatus.PUBLISHED)
        val retiredClaim = approvalDefinition(status = WorkflowDefinitionStatus.RETIRED)

        assertEquals(draft.contentDigest, publishedClaim.contentDigest)
        assertEquals(draft.contentDigest, retiredClaim.contentDigest)
        assertEquals(draft.ref, publishedClaim.ref)
        assertNotEquals(draft, publishedClaim)
        assertEquals(GOLDEN_DEFINITION_CONTENT_DIGEST, draft.contentDigest)

        assertNotEquals(draft.contentDigest, approvalDefinition(tenantId = "租户-北京").contentDigest)
        assertNotEquals(draft.contentDigest, approvalDefinition(definitionId = "知识文件审批").contentDigest)
        assertNotEquals(draft.contentDigest, approvalDefinition(key = "knowledge-approval").contentDigest)
        assertNotEquals(draft.contentDigest, approvalDefinition(version = "2026.07.2").contentDigest)
        assertNotEquals(draft.contentDigest, approvalDefinition(schemaVersion = 2).contentDigest)
        assertNotEquals(draft.contentDigest, approvalDefinition(title = "法律文件复核").contentDigest)
        assertNotEquals(draft.contentDigest, approvalDefinition(description = "不同内容").contentDigest)
        assertNotEquals(
            draft.contentDigest,
            approvalDefinition(nodeOrder = listOf("start", "review", "rejected", "approved")).contentDigest,
        )
    }

    @Test
    fun `human task policy keeps strict quorum explicit capabilities SoD and re-resolution stages`() {
        val selector = WorkflowParticipantSelector.role("财务审批人")
        val quorum = WorkflowApprovalPolicy.quorum(Int.MAX_VALUE)
        assertSame(WorkflowApprovalMode.QUORUM, quorum.mode)
        assertEquals(Int.MAX_VALUE, quorum.requiredApprovals)
        assertNull(WorkflowApprovalPolicy.one().requiredApprovals)
        assertNull(WorkflowApprovalPolicy.all().requiredApprovals)
        assertFailsWith<IllegalArgumentException> { WorkflowApprovalPolicy.quorum(0) }

        val rule = WorkflowHumanTaskParticipantRule.of(selector, quorum)
        assertFailsWith<IllegalArgumentException> {
            WorkflowHumanTaskPolicy.of(
                listOf(rule, WorkflowHumanTaskParticipantRule.of(selector, WorkflowApprovalPolicy.one())),
                WorkflowHumanTaskCapabilities.of(false, false, false, false),
                WorkflowSeparationOfDutiesPolicy.none(),
                listOf(WorkflowParticipantResolutionStage.ACTIVATION),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowHumanTaskPolicy.of(
                listOf(rule),
                WorkflowHumanTaskCapabilities.of(false, false, false, false),
                WorkflowSeparationOfDutiesPolicy.none(),
                listOf(WorkflowParticipantResolutionStage.CLAIM),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowHumanTaskPolicy.of(
                listOf(rule),
                WorkflowHumanTaskCapabilities.of(false, false, false, false),
                WorkflowSeparationOfDutiesPolicy.none(),
                listOf(
                    WorkflowParticipantResolutionStage.ACTIVATION,
                    WorkflowParticipantResolutionStage.ACTIVATION,
                ),
            )
        }

        val extensionStage = WorkflowParticipantResolutionStage.of("corp.escalation")
        val policy = WorkflowHumanTaskPolicy.of(
            listOf(rule),
            WorkflowHumanTaskCapabilities.of(false, true, true, false),
            WorkflowSeparationOfDutiesPolicy.of(true, false),
            listOf(WorkflowParticipantResolutionStage.ACTIVATION, extensionStage),
        )
        assertEquals(extensionStage, policy.resolutionStages.last())
        assertEquals(64, policy.contentDigest.length)
    }

    @Test
    fun `predicate reference binds typed ordered inputs and never embeds expressions`() {
        val amount = WorkflowPredicateInputMapping.of(
            "amount",
            WorkflowPredicateInputSourceKind.WORKFLOW_VARIABLE,
            "/申请/金额",
        )
        val region = WorkflowPredicateInputMapping.of(
            "region",
            WorkflowPredicateInputSourceKind.SUBJECT_ATTRIBUTE,
            "region-code",
        )
        val first = predicate(listOf(amount, region))
        val reversed = predicate(listOf(region, amount))

        assertNotEquals(first.bindingDigest, reversed.bindingDigest)
        assertEquals(DIGEST_A, first.digest)
        assertFailsWith<UnsupportedOperationException> {
            (first.inputMappings as MutableList<WorkflowPredicateInputMapping>).clear()
        }
        assertFailsWith<IllegalArgumentException> { predicate(listOf(amount, amount)) }
        assertFailsWith<IllegalArgumentException> {
            WorkflowPredicateRef.of("corp.rules", "approval", "v1", DIGEST_A.uppercase(), emptyList())
        }
        val sourceExtension = WorkflowPredicateInputSourceKind.of("corp.form-field")
        assertEquals("corp.form-field", sourceExtension.code)

        val methodNames = (WorkflowPredicateRef::class.java.methods +
            WorkflowPredicateInputMapping::class.java.methods).map { method -> method.name.lowercase() }
        assertFalse(methodNames.any { name -> "expression" in name || "script" in name || "eval" in name })
    }

    @Test
    fun `provider and extension nodes require exact descriptor and payload bindings`() {
        assertFailsWith<IllegalArgumentException> {
            WorkflowNodeDefinition.of("service", WorkflowNodeKind.SERVICE_TASK, "服务", null)
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowNodeDefinition.of("timer", WorkflowNodeKind.TIMER_WAIT, "等待", null)
        }
        val serviceA = WorkflowNodeDefinition.serviceTask("service", "服务", null, DIGEST_A, DIGEST_B)
        val serviceB = WorkflowNodeDefinition.serviceTask("service", "服务", null, DIGEST_A, DIGEST_C)
        assertNotEquals(serviceA.contentDigest, serviceB.contentDigest)
        assertEquals(DIGEST_A, serviceA.descriptorDigest)
        assertEquals(DIGEST_B, serviceA.payloadDigest)

        val customKind = WorkflowNodeKind.of("corp.legal-signature")
        val extensionA = WorkflowNodeDefinition.extension("sign", customKind, "签章", null, DIGEST_A, DIGEST_B)
        val extensionB = WorkflowNodeDefinition.extension("sign", customKind, "签章", null, DIGEST_C, DIGEST_B)
        assertNotEquals(extensionA.contentDigest, extensionB.contentDigest)
        assertFailsWith<IllegalArgumentException> {
            WorkflowNodeDefinition.extension("start", WorkflowNodeKind.START, "开始", null, DIGEST_A, DIGEST_B)
        }

        val unknownDefinition = linearDefinition(extensionA, WorkflowDefinitionStatus.PUBLISHED)
        assertEquals(WorkflowNodeKind.of("corp.legal-signature"), unknownDefinition.nodes[1].kind)
        val methodNames = (WorkflowDefinition::class.java.methods + WorkflowNodeDefinition::class.java.methods)
            .map { method -> method.name.lowercase() }
        assertFalse(methodNames.any { name -> "executable" in name || "deploy" in name || "authorize" in name })
    }

    @Test
    fun `transition triggers bind digests and human tasks require unique approved and rejected routes`() {
        val completed = edge("decision", "review", "approved")
        val approved = outcomeEdge(
            "decision",
            "review",
            "approved",
            WorkflowTransitionTrigger.APPROVED,
        )
        val rejected = outcomeEdge(
            "decision",
            "review",
            "approved",
            WorkflowTransitionTrigger.REJECTED,
        )
        assertSame(WorkflowTransitionTrigger.COMPLETED, completed.trigger)
        assertSame(WorkflowTransitionTrigger.APPROVED, approved.trigger)
        assertSame(WorkflowTransitionTrigger.REJECTED, rejected.trigger)
        assertNotEquals(completed.contentDigest, approved.contentDigest)
        assertNotEquals(approved.contentDigest, rejected.contentDigest)
        assertSame(WorkflowTransitionTrigger.APPROVED, WorkflowTransitionTrigger.of("approved"))
        assertEquals("corp.request-changes", WorkflowTransitionTrigger.of("corp.request-changes").code)
        assertFailsWith<IllegalArgumentException> {
            definition(
                listOf(
                    node("ordinary-start", WorkflowNodeKind.START, "开始"),
                    node("ordinary-end", WorkflowNodeKind.END, "结束"),
                ),
                listOf(
                    outcomeEdge(
                        "ordinary-approved",
                        "ordinary-start",
                        "ordinary-end",
                        WorkflowTransitionTrigger.APPROVED,
                    ),
                ),
            )
        }

        val start = node("start", WorkflowNodeKind.START, "开始")
        val review = WorkflowNodeDefinition.humanTask("review", "审批", null, humanPolicy())
        val accepted = node("accepted", WorkflowNodeKind.END, "通过")
        val denied = node("denied", WorkflowNodeKind.END, "拒绝")
        val valid = definition(
            listOf(start, review, accepted, denied),
            listOf(
                edge("start-review", "start", "review"),
                outcomeEdge("review-accepted", "review", "accepted", WorkflowTransitionTrigger.APPROVED),
                outcomeEdge("review-denied", "review", "denied", WorkflowTransitionTrigger.REJECTED),
            ),
        )
        assertEquals(3, valid.transitions.size)

        assertFailsWith<IllegalArgumentException> {
            definition(
                listOf(start, review, accepted),
                listOf(
                    edge("start-review", "start", "review"),
                    outcomeEdge("review-accepted", "review", "accepted", WorkflowTransitionTrigger.APPROVED),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            definition(
                listOf(start, review, accepted, denied),
                listOf(
                    edge("start-review", "start", "review"),
                    edge("review-accepted", "review", "accepted"),
                    outcomeEdge("review-denied", "review", "denied", WorkflowTransitionTrigger.REJECTED),
                ),
            )
        }
        val acceptedAgain = node("accepted-again", WorkflowNodeKind.END, "再次通过")
        assertFailsWith<IllegalArgumentException> {
            definition(
                listOf(start, review, accepted, acceptedAgain, denied),
                listOf(
                    edge("start-review", "start", "review"),
                    outcomeEdge("review-accepted", "review", "accepted", WorkflowTransitionTrigger.APPROVED),
                    outcomeEdge(
                        "review-accepted-again",
                        "review",
                        "accepted-again",
                        WorkflowTransitionTrigger.APPROVED,
                    ),
                    outcomeEdge("review-denied", "review", "denied", WorkflowTransitionTrigger.REJECTED),
                ),
            )
        }
    }

    @Test
    fun `lint rejects malformed endpoints shapes conditions reachability and no-exit cycles`() {
        val start = node("start", WorkflowNodeKind.START, "开始")
        val end = node("end", WorkflowNodeKind.END, "结束")
        assertFailsWith<IllegalArgumentException> {
            definition(listOf(start, start, end), listOf(edge("t", "start", "end")))
        }
        assertFailsWith<IllegalArgumentException> {
            definition(
                listOf(start, node("start2", WorkflowNodeKind.START, "另一个开始"), end),
                listOf(edge("t1", "start", "end"), edge("t2", "start2", "end")),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            definition(listOf(start, end), listOf(edge("t", "start", "missing")))
        }

        val service = WorkflowNodeDefinition.serviceTask("service", "服务", null, DIGEST_A, DIGEST_B)
        assertFailsWith<IllegalArgumentException> {
            definition(
                listOf(start, service, end),
                listOf(
                    edge("t1", "start", "service"),
                    WorkflowTransitionDefinition.conditional("t2", "service", "end", predicate()),
                ),
            )
        }

        val gateway = node("route", WorkflowNodeKind.EXCLUSIVE_GATEWAY, "路由")
        val end2 = node("end2", WorkflowNodeKind.END, "结束二")
        assertFailsWith<IllegalArgumentException> {
            definition(
                listOf(start, gateway, end, end2),
                listOf(
                    edge("t1", "start", "route"),
                    edge("default-first", "route", "end"),
                    WorkflowTransitionDefinition.conditional("condition-last", "route", "end2", predicate()),
                ),
            )
        }

        val merge = node("merge", WorkflowNodeKind.EXCLUSIVE_GATEWAY, "循环合并")
        val loop = WorkflowNodeDefinition.serviceTask("loop", "循环任务", null, DIGEST_A, DIGEST_B)
        assertFailsWith<IllegalArgumentException> {
            definition(
                listOf(start, gateway, end, merge, loop),
                listOf(
                    edge("s-g", "start", "route"),
                    WorkflowTransitionDefinition.conditional("g-end", "route", "end", predicate()),
                    edge("g-merge", "route", "merge"),
                    edge("merge-loop", "merge", "loop"),
                    edge("loop-merge", "loop", "merge"),
                ),
            )
        }
    }

    @Test
    fun `lint accepts paired parallel regions and rejects bad or bypassed joins`() {
        val valid = parallelDefinition()
        assertEquals(6, valid.nodes.size)
        assertEquals(6, valid.transitions.size)

        val badJoin = WorkflowNodeDefinition.parallelJoin("join", "wrong-split", "合并", null)
        assertFailsWith<IllegalArgumentException> { parallelDefinition(join = badJoin) }

        val exclusive = node("exclusive", WorkflowNodeKind.EXCLUSIVE_GATEWAY, "互斥分支")
        val left = WorkflowNodeDefinition.serviceTask("left", "左分支", null, DIGEST_A, DIGEST_B)
        val right = WorkflowNodeDefinition.serviceTask("right", "右分支", null, DIGEST_A, DIGEST_C)
        val orphanJoin = WorkflowNodeDefinition.parallelJoin("orphan-join", "missing-split", "孤立合并", null)
        assertFailsWith<IllegalArgumentException> {
            definition(
                listOf(
                    node("orphan-start", WorkflowNodeKind.START, "开始"),
                    exclusive,
                    left,
                    right,
                    orphanJoin,
                    node("orphan-end", WorkflowNodeKind.END, "结束"),
                ),
                listOf(
                    edge("os-e", "orphan-start", "exclusive"),
                    WorkflowTransitionDefinition.conditional("e-left", "exclusive", "left", predicate()),
                    edge("e-right", "exclusive", "right"),
                    edge("left-join", "left", "orphan-join"),
                    edge("right-join", "right", "orphan-join"),
                    edge("join-oe", "orphan-join", "orphan-end"),
                ),
            )
        }

        val start = node("start", WorkflowNodeKind.START, "开始")
        val split = WorkflowNodeDefinition.parallelSplit("split", "join", "并行", null)
        val route = node("route", WorkflowNodeKind.EXCLUSIVE_GATEWAY, "分支路由")
        val branch = WorkflowNodeDefinition.serviceTask("branch", "分支", null, DIGEST_A, DIGEST_B)
        val join = WorkflowNodeDefinition.parallelJoin("join", "split", "合并", null)
        val end = node("end", WorkflowNodeKind.END, "结束")
        assertFailsWith<IllegalArgumentException> {
            definition(
                listOf(start, split, route, branch, join, end),
                listOf(
                    edge("s-split", "start", "split"),
                    edge("split-route", "split", "route"),
                    edge("split-branch", "split", "branch"),
                    WorkflowTransitionDefinition.conditional("route-join", "route", "join", predicate()),
                    edge("route-end", "route", "end"),
                    edge("branch-join", "branch", "join"),
                    edge("join-end", "join", "end"),
                ),
            )
        }
    }

    @Test
    fun `lint rejects twelve node crossing parallel pairs`() {
        val nodes = listOf(
            node("start", WorkflowNodeKind.START, "开始"),
            WorkflowNodeDefinition.parallelSplit("outer-split", "outer-join", "外层分流", null),
            WorkflowNodeDefinition.parallelSplit("inner-split", "inner-join", "内层分流", null),
            WorkflowNodeDefinition.serviceTask("inner-left", "内层甲", null, DIGEST_A, DIGEST_B),
            WorkflowNodeDefinition.serviceTask("inner-right", "内层乙", null, DIGEST_A, DIGEST_C),
            WorkflowNodeDefinition.serviceTask("outer-right", "外层乙", null, DIGEST_B, DIGEST_C),
            WorkflowNodeDefinition.parallelJoin("outer-join", "outer-split", "外层合流", null),
            node("fan-out", WorkflowNodeKind.EXCLUSIVE_GATEWAY, "重新分流"),
            WorkflowNodeDefinition.serviceTask("post-left", "后段甲", null, DIGEST_A, DIGEST_B),
            WorkflowNodeDefinition.serviceTask("post-right", "后段乙", null, DIGEST_A, DIGEST_C),
            WorkflowNodeDefinition.parallelJoin("inner-join", "inner-split", "内层合流", null),
            node("end", WorkflowNodeKind.END, "结束"),
        )
        val transitions = listOf(
            edge("start-outer", "start", "outer-split"),
            edge("outer-inner", "outer-split", "inner-split"),
            edge("outer-right", "outer-split", "outer-right"),
            edge("inner-left", "inner-split", "inner-left"),
            edge("inner-right", "inner-split", "inner-right"),
            edge("left-outer-join", "inner-left", "outer-join"),
            edge("right-outer-join", "inner-right", "outer-join"),
            edge("outer-right-join", "outer-right", "outer-join"),
            edge("outer-join-fan", "outer-join", "fan-out"),
            WorkflowTransitionDefinition.conditional("fan-post-left", "fan-out", "post-left", predicate()),
            edge("fan-post-right", "fan-out", "post-right"),
            edge("post-left-inner-join", "post-left", "inner-join"),
            edge("post-right-inner-join", "post-right", "inner-join"),
            edge("inner-join-end", "inner-join", "end"),
        )

        assertEquals(12, nodes.size)
        assertEquals(14, transitions.size)
        assertFailsWith<IllegalArgumentException> { definition(nodes, transitions) }
    }

    @Test
    fun `lint accepts strictly nested parallel pairs`() {
        val nested = definition(
            listOf(
                node("start", WorkflowNodeKind.START, "开始"),
                WorkflowNodeDefinition.parallelSplit("outer-split", "outer-join", "外层分流", null),
                WorkflowNodeDefinition.parallelSplit("inner-split", "inner-join", "内层分流", null),
                WorkflowNodeDefinition.serviceTask("inner-left", "内层甲", null, DIGEST_A, DIGEST_B),
                WorkflowNodeDefinition.serviceTask("inner-right", "内层乙", null, DIGEST_A, DIGEST_C),
                WorkflowNodeDefinition.parallelJoin("inner-join", "inner-split", "内层合流", null),
                WorkflowNodeDefinition.serviceTask("after-inner", "内层完成", null, DIGEST_B, DIGEST_C),
                WorkflowNodeDefinition.serviceTask("outer-right", "外层乙", null, DIGEST_A, DIGEST_C),
                WorkflowNodeDefinition.parallelJoin("outer-join", "outer-split", "外层合流", null),
                node("end", WorkflowNodeKind.END, "结束"),
            ),
            listOf(
                edge("start-outer", "start", "outer-split"),
                edge("outer-inner", "outer-split", "inner-split"),
                edge("outer-right", "outer-split", "outer-right"),
                edge("inner-left", "inner-split", "inner-left"),
                edge("inner-right", "inner-split", "inner-right"),
                edge("left-inner-join", "inner-left", "inner-join"),
                edge("right-inner-join", "inner-right", "inner-join"),
                edge("inner-join-after", "inner-join", "after-inner"),
                edge("after-outer-join", "after-inner", "outer-join"),
                edge("outer-right-join", "outer-right", "outer-join"),
                edge("outer-join-end", "outer-join", "end"),
            ),
        )

        assertEquals(10, nested.nodes.size)
        assertEquals(11, nested.transitions.size)
    }

    @Test
    fun `lint rejects early branch convergence and external parallel region entry`() {
        val earlyMergeNodes = listOf(
            node("start", WorkflowNodeKind.START, "开始"),
            WorkflowNodeDefinition.parallelSplit("split", "join", "并行", null),
            WorkflowNodeDefinition.serviceTask("left", "分支甲", null, DIGEST_A, DIGEST_B),
            WorkflowNodeDefinition.serviceTask("right", "分支乙", null, DIGEST_A, DIGEST_C),
            node("early-merge", WorkflowNodeKind.EXCLUSIVE_GATEWAY, "提前合流"),
            node("fan-out", WorkflowNodeKind.EXCLUSIVE_GATEWAY, "重新分流"),
            WorkflowNodeDefinition.serviceTask("late-left", "后段甲", null, DIGEST_B, DIGEST_C),
            WorkflowNodeDefinition.serviceTask("late-right", "后段乙", null, DIGEST_A, DIGEST_C),
            WorkflowNodeDefinition.parallelJoin("join", "split", "配对合流", null),
            node("end", WorkflowNodeKind.END, "结束"),
        )
        val earlyMergeTransitions = listOf(
            edge("start-split", "start", "split"),
            edge("split-left", "split", "left"),
            edge("split-right", "split", "right"),
            edge("left-merge", "left", "early-merge"),
            edge("right-merge", "right", "early-merge"),
            edge("merge-fan", "early-merge", "fan-out"),
            WorkflowTransitionDefinition.conditional("fan-left", "fan-out", "late-left", predicate()),
            edge("fan-right", "fan-out", "late-right"),
            edge("late-left-join", "late-left", "join"),
            edge("late-right-join", "late-right", "join"),
            edge("join-end", "join", "end"),
        )
        assertFailsWith<IllegalArgumentException> { definition(earlyMergeNodes, earlyMergeTransitions) }

        val externalEntryNodes = listOf(
            node("entry-start", WorkflowNodeKind.START, "开始"),
            node("entry-route", WorkflowNodeKind.EXCLUSIVE_GATEWAY, "入口路由"),
            WorkflowNodeDefinition.parallelSplit("entry-split", "entry-join", "并行", null),
            WorkflowNodeDefinition.serviceTask("outside", "区域外路径", null, DIGEST_A, DIGEST_B),
            WorkflowNodeDefinition.serviceTask("entry-left", "分支甲", null, DIGEST_A, DIGEST_C),
            WorkflowNodeDefinition.serviceTask("entry-right", "分支乙", null, DIGEST_B, DIGEST_C),
            node("inside-merge", WorkflowNodeKind.EXCLUSIVE_GATEWAY, "区域内合流"),
            WorkflowNodeDefinition.serviceTask("inside", "区域内节点", null, DIGEST_A, DIGEST_B),
            WorkflowNodeDefinition.parallelJoin("entry-join", "entry-split", "合流", null),
            node("entry-end", WorkflowNodeKind.END, "结束"),
        )
        val externalEntryTransitions = listOf(
            edge("entry-start-route", "entry-start", "entry-route"),
            WorkflowTransitionDefinition.conditional("route-split", "entry-route", "entry-split", predicate()),
            edge("route-outside", "entry-route", "outside"),
            edge("split-left", "entry-split", "entry-left"),
            edge("split-right", "entry-split", "entry-right"),
            edge("left-inside-merge", "entry-left", "inside-merge"),
            edge("outside-inside-merge", "outside", "inside-merge"),
            edge("inside-merge-node", "inside-merge", "inside"),
            edge("inside-join", "inside", "entry-join"),
            edge("right-join", "entry-right", "entry-join"),
            edge("entry-join-end", "entry-join", "entry-end"),
        )
        assertFailsWith<IllegalArgumentException> { definition(externalEntryNodes, externalEntryTransitions) }
    }

    @Test
    fun `fixed Unicode profile permits bounded Chinese multiline text and rejects unstable controls`() {
        val description = "第一行\r\n第二行\t说明"
        val start = WorkflowNodeDefinition.of("start", WorkflowNodeKind.START, "申请😀", description)
        assertEquals(description, start.description)
        assertFailsWith<IllegalArgumentException> {
            WorkflowNodeDefinition.of("start", WorkflowNodeKind.START, "换\n行", null)
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowNodeDefinition.of("start", WorkflowNodeKind.START, "开始", "\n边界")
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowNodeDefinition.of("start", WorkflowNodeKind.START, "开始", "含有\u2028分隔")
        }
        val changingFormatCodePoint = String(Character.toChars(0x13430))
        assertFailsWith<IllegalArgumentException> {
            WorkflowNodeDefinition.of("start", WorkflowNodeKind.START, "开始", "含有${changingFormatCodePoint}格式")
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowNodeDefinition.of("start", WorkflowNodeKind.START, "\uD800", null)
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowNodeDefinition.of("start", WorkflowNodeKind.START, "中".repeat(86), null)
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowNodeDefinition.of("start", WorkflowNodeKind.START, "开始", "a".repeat(8193))
        }
    }

    @Test
    fun `all new values keep exact redacted logging`() {
        val values = listOf(
            WorkflowDefinitionStatus.of("corp.secret-status"),
            WorkflowNodeKind.of("corp.secret-kind"),
            WorkflowApprovalMode.of("corp.secret-mode"),
            WorkflowParticipantResolutionStage.of("corp.secret-stage"),
            WorkflowPredicateInputSourceKind.of("corp.secret-source"),
            WorkflowTransitionTrigger.of("corp.secret-trigger"),
            WorkflowApprovalPolicy.quorum(2),
            WorkflowHumanTaskCapabilities.of(true, true, true, true),
            WorkflowSeparationOfDutiesPolicy.of(true, true),
            WorkflowPredicateInputMapping.of("secret", WorkflowPredicateInputSourceKind.CONTEXT_VALUE, "secret"),
            predicate(),
            node("start", WorkflowNodeKind.START, "机密"),
            edge("edge", "start", "end"),
        )
        values.forEach { value ->
            assertEquals("${value.javaClass.simpleName}(<redacted>)", value.toString())
        }
    }

    private fun approvalDefinition(
        tenantId: String = "租户-天津",
        definitionId: String = "法律文件审批",
        key: String = "legal-document-approval",
        version: String = "2026.07.1",
        schemaVersion: Int = 1,
        status: WorkflowDefinitionStatus = WorkflowDefinitionStatus.DRAFT,
        title: String = "法律文件审批流程",
        description: String? = "法务与业务负责人复核\n适用于正式法律文件",
        policy: WorkflowHumanTaskPolicy = humanPolicy(),
        nodeOrder: List<String> = listOf("start", "review", "approved", "rejected"),
    ): WorkflowDefinition {
        val byId = listOf(
            node("start", WorkflowNodeKind.START, "开始"),
            WorkflowNodeDefinition.humanTask("review", "人工审批", "候选人按组织快照解析", policy),
            node("approved", WorkflowNodeKind.END, "通过"),
            node("rejected", WorkflowNodeKind.END, "拒绝"),
        ).associateBy { node -> node.nodeId }
        val nodes = nodeOrder.map { nodeId -> byId.getValue(nodeId) }
        val transitions = listOf(
            edge("start-review", "start", "review"),
            outcomeEdge("review-approved", "review", "approved", WorkflowTransitionTrigger.APPROVED),
            outcomeEdge("review-rejected", "review", "rejected", WorkflowTransitionTrigger.REJECTED),
        )
        return WorkflowDefinition.of(
            tenantId,
            definitionId,
            key,
            version,
            schemaVersion,
            status,
            title,
            description,
            nodes,
            transitions,
        )
    }

    private fun humanPolicy(): WorkflowHumanTaskPolicy = WorkflowHumanTaskPolicy.of(
        listOf(
            WorkflowHumanTaskParticipantRule.of(
                WorkflowParticipantSelector.group("法务审批组"),
                WorkflowApprovalPolicy.quorum(2),
            ),
        ),
        WorkflowHumanTaskCapabilities.of(true, true, false, true),
        WorkflowSeparationOfDutiesPolicy.of(true, true),
        listOf(
            WorkflowParticipantResolutionStage.ACTIVATION,
            WorkflowParticipantResolutionStage.CLAIM,
            WorkflowParticipantResolutionStage.DECISION,
        ),
    )

    private fun predicate(
        mappings: List<WorkflowPredicateInputMapping> = listOf(
            WorkflowPredicateInputMapping.of(
                "decision",
                WorkflowPredicateInputSourceKind.WORKFLOW_VARIABLE,
                "approval-decision",
            ),
        ),
    ): WorkflowPredicateRef = WorkflowPredicateRef.of(
        "corp.rules",
        "approval-accepted",
        "2026.07",
        DIGEST_A,
        mappings,
    )

    private fun node(nodeId: String, kind: WorkflowNodeKind, title: String): WorkflowNodeDefinition =
        WorkflowNodeDefinition.of(nodeId, kind, title, null)

    private fun edge(
        transitionId: String,
        fromNodeId: String,
        toNodeId: String,
    ): WorkflowTransitionDefinition = WorkflowTransitionDefinition.unconditional(
        transitionId,
        fromNodeId,
        toNodeId,
    )

    private fun outcomeEdge(
        transitionId: String,
        fromNodeId: String,
        toNodeId: String,
        trigger: WorkflowTransitionTrigger,
    ): WorkflowTransitionDefinition = WorkflowTransitionDefinition.unconditional(
        transitionId,
        fromNodeId,
        toNodeId,
        trigger,
    )

    private fun definition(
        nodes: List<WorkflowNodeDefinition>,
        transitions: List<WorkflowTransitionDefinition>,
    ): WorkflowDefinition = WorkflowDefinition.of(
        "tenant",
        "definition",
        "definition-key",
        "v1",
        1,
        WorkflowDefinitionStatus.DRAFT,
        "测试流程",
        null,
        nodes,
        transitions,
    )

    private fun parallelDefinition(
        join: WorkflowNodeDefinition = WorkflowNodeDefinition.parallelJoin("join", "split", "合并", null),
    ): WorkflowDefinition {
        val nodes = listOf(
            node("start", WorkflowNodeKind.START, "开始"),
            WorkflowNodeDefinition.parallelSplit("split", "join", "并行", null),
            WorkflowNodeDefinition.serviceTask("a", "分支甲", null, DIGEST_A, DIGEST_B),
            WorkflowNodeDefinition.serviceTask("b", "分支乙", null, DIGEST_A, DIGEST_C),
            join,
            node("end", WorkflowNodeKind.END, "结束"),
        )
        val transitions = listOf(
            edge("start-split", "start", "split"),
            edge("split-a", "split", "a"),
            edge("split-b", "split", "b"),
            edge("a-join", "a", "join"),
            edge("b-join", "b", "join"),
            edge("join-end", "join", "end"),
        )
        return definition(nodes, transitions)
    }

    private fun linearDefinition(
        middle: WorkflowNodeDefinition,
        status: WorkflowDefinitionStatus,
    ): WorkflowDefinition = WorkflowDefinition.of(
        "tenant",
        "definition",
        "definition-key",
        "v1",
        1,
        status,
        "扩展流程",
        null,
        listOf(
            node("start", WorkflowNodeKind.START, "开始"),
            middle,
            node("end", WorkflowNodeKind.END, "结束"),
        ),
        listOf(
            edge("start-middle", "start", middle.nodeId),
            edge("middle-end", middle.nodeId, "end"),
        ),
    )

    private companion object {
        const val DIGEST_A = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val DIGEST_B = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
        const val DIGEST_C = "1111111111111111111111111111111111111111111111111111111111111111"
        const val GOLDEN_DEFINITION_CONTENT_DIGEST =
            "0ca7358208257e87c4438aab74aa5bd402250a38caa3223a44dd6f3ae603e3b6"
    }
}
