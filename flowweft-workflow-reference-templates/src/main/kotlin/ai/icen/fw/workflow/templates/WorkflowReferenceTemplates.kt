package ai.icen.fw.workflow.templates

import ai.icen.fw.workflow.api.WorkflowApprovalPolicy
import ai.icen.fw.workflow.api.WorkflowDefinition
import ai.icen.fw.workflow.api.WorkflowDefinitionStatus
import ai.icen.fw.workflow.api.WorkflowHumanTaskCapabilities
import ai.icen.fw.workflow.api.WorkflowHumanTaskEvidenceBinding
import ai.icen.fw.workflow.api.WorkflowHumanTaskParticipantRule
import ai.icen.fw.workflow.api.WorkflowHumanTaskPolicy
import ai.icen.fw.workflow.api.WorkflowNodeDefinition
import ai.icen.fw.workflow.api.WorkflowNodeKind
import ai.icen.fw.workflow.api.WorkflowParticipantMembershipStrategy
import ai.icen.fw.workflow.api.WorkflowParticipantResolutionStage
import ai.icen.fw.workflow.api.WorkflowParticipantSelector
import ai.icen.fw.workflow.api.WorkflowPredicateRef
import ai.icen.fw.workflow.api.WorkflowSeparationOfDutiesPolicy
import ai.icen.fw.workflow.api.WorkflowTransitionDefinition
import ai.icen.fw.workflow.api.WorkflowTransitionTrigger

/** Stable binding slots used by the four fixed 1.0 reference factories. */
object WorkflowReferenceTemplateSlots {
    const val ORGANIZATION_PROFILE = "organization"
    const val FORM_PROFILE = "form"
    const val PREDICATE_PROFILE = "predicate"
    const val CALENDAR_PROFILE = "calendar"
    const val REVISION_SERVICE_PROFILE = "subject-revision-service"

    const val LEAVE_EVIDENCE = "leave.evidence"
    const val LEAVE_SECOND_MANAGER_PREDICATE = "leave.second-manager-required"

    const val EXPENSE_EVIDENCE = "expense.evidence"
    const val EXPENSE_COST_CENTER_OWNER = "expense.cost-center-owner"
    const val EXPENSE_FINANCE_REVIEWER = "expense.finance-reviewer"
    const val EXPENSE_HIGH_VALUE_REVIEWER = "expense.high-value-reviewer"
    const val EXPENSE_HIGH_VALUE_PREDICATE = "expense.high-value-required"

    const val KNOWLEDGE_EVIDENCE = "knowledge.evidence"
    const val KNOWLEDGE_CONTENT_OWNER = "knowledge.content-owner"
    const val KNOWLEDGE_MANAGER = "knowledge.manager"
    const val KNOWLEDGE_SECURITY_REVIEWER = "knowledge.security-reviewer"
    const val KNOWLEDGE_SECURITY_PREDICATE = "knowledge.security-review-required"
    const val KNOWLEDGE_REVISION_SERVICE = "knowledge.subject-revision-cycle"

    const val LEGAL_EVIDENCE = "legal.evidence"
    const val LEGAL_BUSINESS_OWNER = "legal.business-owner"
    const val LEGAL_COUNSEL = "legal.counsel"
    const val LEGAL_EXPERT = "legal.expert"
    const val LEGAL_DEPARTMENT_LEADERS = "legal.department-leaders"
    const val LEGAL_EXPERT_PREDICATE = "legal.expert-review-required"
    const val LEGAL_REVISION_SERVICE = "legal.subject-revision-cycle"
}

/** Fixed-version, vendor-neutral template factories. Every result is DRAFT and linted. */
class WorkflowReferenceTemplates private constructor() {
    companion object {
        const val TEMPLATE_VERSION = "1.0.0"

        @JvmStatic
        fun leave(
            tenantId: String,
            definitionId: String,
            bindings: WorkflowReferenceTemplateBindings,
        ): WorkflowReferenceTemplate {
            val organization = profile(
                bindings,
                WorkflowReferenceTemplateSlots.ORGANIZATION_PROFILE,
                WorkflowTemplateProfileKind.ORGANIZATION,
            )
            val form = profile(bindings, WorkflowReferenceTemplateSlots.FORM_PROFILE, WorkflowTemplateProfileKind.FORM)
            val predicateProfile = profile(
                bindings,
                WorkflowReferenceTemplateSlots.PREDICATE_PROFILE,
                WorkflowTemplateProfileKind.PREDICATE,
            )
            val calendar = profile(
                bindings,
                WorkflowReferenceTemplateSlots.CALENDAR_PROFILE,
                WorkflowTemplateProfileKind.BUSINESS_CALENDAR,
            )
            val evidence = bindings.evidence(WorkflowReferenceTemplateSlots.LEAVE_EVIDENCE)
            val secondManagerPredicate = bindings.predicate(
                WorkflowReferenceTemplateSlots.LEAVE_SECOND_MANAGER_PREDICATE,
            )
            val managerLevelOne = WorkflowParticipantSelector.initiatorManagerChain(1, 1)
            val managerLevelTwo = WorkflowParticipantSelector.initiatorManagerChain(2, 2)
            val managerOne = human(
                "manager-level-1",
                "直属负责人审批",
                managerLevelOne,
                WorkflowApprovalPolicy.one(),
                evidence,
                addSign = false,
                delegation = true,
            )
            val managerTwo = human(
                "manager-level-2",
                "二级负责人审批",
                managerLevelTwo,
                WorkflowApprovalPolicy.one(),
                evidence,
                addSign = false,
                delegation = true,
            )
            val nodes = listOf(
                structural("start", WorkflowNodeKind.START, "开始"),
                managerOne,
                structural("needs-level-2", WorkflowNodeKind.EXCLUSIVE_GATEWAY, "是否需要二级审批"),
                managerTwo,
                structural("approved", WorkflowNodeKind.END, "通过"),
                structural("rejected", WorkflowNodeKind.END, "驳回"),
            )
            val transitions = listOf(
                edge("leave-start-manager-l1", "start", "manager-level-1"),
                outcome("leave-manager-l1-approved", "manager-level-1", "needs-level-2", approved = true),
                outcome("leave-manager-l1-rejected", "manager-level-1", "rejected", approved = false),
                conditional(
                    "leave-needs-l2-required",
                    "needs-level-2",
                    "manager-level-2",
                    secondManagerPredicate,
                ),
                edge("leave-needs-l2-default", "needs-level-2", "approved"),
                outcome("leave-manager-l2-approved", "manager-level-2", "approved", approved = true),
                outcome("leave-manager-l2-rejected", "manager-level-2", "rejected", approved = false),
            )
            return template(
                "flowweft.leave",
                tenantId,
                definitionId,
                "flowweft-reference-leave",
                "请假审批参考模板",
                "两级管理链与条件二级审批；HR 抄送和代理人通知必须由宿主通知策略显式绑定。",
                nodes,
                transitions,
                bindings.subject,
                listOf(
                    selectorBinding(managerOne, 0, "leave.manager-level-1", managerLevelOne, organization),
                    selectorBinding(managerTwo, 0, "leave.manager-level-2", managerLevelTwo, organization),
                ),
                formBindings(listOf(managerOne, managerTwo), evidence, form),
                listOf(
                    WorkflowTemplatePredicateBinding.of(
                        "leave-needs-l2-required",
                        WorkflowReferenceTemplateSlots.LEAVE_SECOND_MANAGER_PREDICATE,
                        secondManagerPredicate.bindingDigest,
                        predicateProfile,
                    ),
                ),
                emptyList(),
                sla(listOf(managerOne, managerTwo), calendar, 14_400L, 28_800L, 43_200L),
                emptyList(),
                listOf(
                    simulation(
                        "leave-single-level-approved",
                        choices(
                            "manager-level-1", 1, "leave-manager-l1-approved",
                            "needs-level-2", 1, "leave-needs-l2-default",
                        ),
                        "start", "manager-level-1", "needs-level-2", "approved",
                    ),
                    simulation(
                        "leave-two-level-approved",
                        choices(
                            "manager-level-1", 1, "leave-manager-l1-approved",
                            "needs-level-2", 1, "leave-needs-l2-required",
                            "manager-level-2", 1, "leave-manager-l2-approved",
                        ),
                        "start", "manager-level-1", "needs-level-2", "manager-level-2", "approved",
                    ),
                    simulation(
                        "leave-rejected",
                        choices("manager-level-1", 1, "leave-manager-l1-rejected"),
                        "start", "manager-level-1", "rejected",
                    ),
                ),
            )
        }

        @JvmStatic
        fun expense(
            tenantId: String,
            definitionId: String,
            bindings: WorkflowReferenceTemplateBindings,
        ): WorkflowReferenceTemplate {
            val organization = organization(bindings)
            val form = form(bindings)
            val predicateProfile = predicateProfile(bindings)
            val calendar = calendar(bindings)
            val evidence = bindings.evidence(WorkflowReferenceTemplateSlots.EXPENSE_EVIDENCE)
            val managerSelector = WorkflowParticipantSelector.initiatorManagerChain(1, 1)
            val costSelector = bindings.selector(WorkflowReferenceTemplateSlots.EXPENSE_COST_CENTER_OWNER)
            val financeSelector = bindings.selector(WorkflowReferenceTemplateSlots.EXPENSE_FINANCE_REVIEWER)
            val highSelector = bindings.selector(WorkflowReferenceTemplateSlots.EXPENSE_HIGH_VALUE_REVIEWER)
            val highPredicate = bindings.predicate(WorkflowReferenceTemplateSlots.EXPENSE_HIGH_VALUE_PREDICATE)
            val manager = human(
                "manager-review",
                "直属负责人审批",
                managerSelector,
                WorkflowApprovalPolicy.one(),
                evidence,
                false,
                true,
            )
            val highReview = human(
                "high-value-review",
                "高额复核",
                highSelector,
                WorkflowApprovalPolicy.all(),
                evidence,
                true,
                false,
            )
            val costReview = human(
                "cost-center-review",
                "成本中心审批",
                costSelector,
                WorkflowApprovalPolicy.one(),
                evidence,
                false,
                false,
            )
            val financeReview = human(
                "finance-review",
                "财务审批",
                financeSelector,
                WorkflowApprovalPolicy.quorum(2),
                evidence,
                true,
                false,
            )
            val nodes = listOf(
                structural("start", WorkflowNodeKind.START, "开始"),
                manager,
                structural("high-value-route", WorkflowNodeKind.EXCLUSIVE_GATEWAY, "高额路由"),
                highReview,
                structural("cost-entry", WorkflowNodeKind.EXCLUSIVE_GATEWAY, "成本中心入口"),
                costReview,
                financeReview,
                structural("approved", WorkflowNodeKind.END, "通过"),
                structural("rejected", WorkflowNodeKind.END, "驳回"),
            )
            val transitions = listOf(
                edge("expense-start-manager", "start", "manager-review"),
                outcome("expense-manager-approved", "manager-review", "high-value-route", true),
                outcome("expense-manager-rejected", "manager-review", "rejected", false),
                conditional("expense-high-required", "high-value-route", "high-value-review", highPredicate),
                edge("expense-high-default", "high-value-route", "cost-entry"),
                outcome("expense-high-approved", "high-value-review", "cost-entry", true),
                outcome("expense-high-rejected", "high-value-review", "rejected", false),
                edge("expense-cost-entry", "cost-entry", "cost-center-review"),
                outcome("expense-cost-approved", "cost-center-review", "finance-review", true),
                outcome("expense-cost-rejected", "cost-center-review", "rejected", false),
                outcome("expense-finance-approved", "finance-review", "approved", true),
                outcome("expense-finance-rejected", "finance-review", "rejected", false),
            )
            return template(
                "flowweft.expense",
                tenantId,
                definitionId,
                "flowweft-reference-expense",
                "报销审批参考模板",
                "管理链、金额规则、成本中心、高额复核和财务职责分离。",
                nodes,
                transitions,
                bindings.subject,
                listOf(
                    selectorBinding(manager, 0, "expense.manager-level-1", managerSelector, organization),
                    selectorBinding(highReview, 0, WorkflowReferenceTemplateSlots.EXPENSE_HIGH_VALUE_REVIEWER, highSelector, organization),
                    selectorBinding(costReview, 0, WorkflowReferenceTemplateSlots.EXPENSE_COST_CENTER_OWNER, costSelector, organization),
                    selectorBinding(financeReview, 0, WorkflowReferenceTemplateSlots.EXPENSE_FINANCE_REVIEWER, financeSelector, organization),
                ),
                formBindings(listOf(manager, highReview, costReview, financeReview), evidence, form),
                listOf(
                    WorkflowTemplatePredicateBinding.of(
                        "expense-high-required",
                        WorkflowReferenceTemplateSlots.EXPENSE_HIGH_VALUE_PREDICATE,
                        highPredicate.bindingDigest,
                        predicateProfile,
                    ),
                ),
                emptyList(),
                sla(listOf(manager, highReview, costReview, financeReview), calendar, 14_400L, 28_800L, 43_200L),
                emptyList(),
                listOf(
                    simulation(
                        "expense-standard-approved",
                        choices(
                            "manager-review", 1, "expense-manager-approved",
                            "high-value-route", 1, "expense-high-default",
                            "cost-center-review", 1, "expense-cost-approved",
                            "finance-review", 1, "expense-finance-approved",
                        ),
                        "start", "manager-review", "high-value-route", "cost-entry", "cost-center-review",
                        "finance-review", "approved",
                    ),
                    simulation(
                        "expense-high-value-approved",
                        choices(
                            "manager-review", 1, "expense-manager-approved",
                            "high-value-route", 1, "expense-high-required",
                            "high-value-review", 1, "expense-high-approved",
                            "cost-center-review", 1, "expense-cost-approved",
                            "finance-review", 1, "expense-finance-approved",
                        ),
                        "start", "manager-review", "high-value-route", "high-value-review", "cost-entry",
                        "cost-center-review", "finance-review", "approved",
                    ),
                ),
            )
        }

        @JvmStatic
        fun knowledgeDocument(
            tenantId: String,
            definitionId: String,
            bindings: WorkflowReferenceTemplateBindings,
        ): WorkflowReferenceTemplate = documentTemplate(
            legal = false,
            tenantId = tenantId,
            definitionId = definitionId,
            bindings = bindings,
        )

        @JvmStatic
        fun legalDocument(
            tenantId: String,
            definitionId: String,
            bindings: WorkflowReferenceTemplateBindings,
        ): WorkflowReferenceTemplate = documentTemplate(
            legal = true,
            tenantId = tenantId,
            definitionId = definitionId,
            bindings = bindings,
        )

        private fun documentTemplate(
            legal: Boolean,
            tenantId: String,
            definitionId: String,
            bindings: WorkflowReferenceTemplateBindings,
        ): WorkflowReferenceTemplate = if (legal) {
            legalTemplate(tenantId, definitionId, bindings)
        } else {
            knowledgeTemplate(tenantId, definitionId, bindings)
        }

        private fun knowledgeTemplate(
            tenantId: String,
            definitionId: String,
            bindings: WorkflowReferenceTemplateBindings,
        ): WorkflowReferenceTemplate {
            val organization = organization(bindings)
            val form = form(bindings)
            val predicateProfile = predicateProfile(bindings)
            val calendar = calendar(bindings)
            val serviceProfile = serviceProfile(bindings)
            val evidence = bindings.evidence(WorkflowReferenceTemplateSlots.KNOWLEDGE_EVIDENCE)
            val ownerSelector = bindings.selector(WorkflowReferenceTemplateSlots.KNOWLEDGE_CONTENT_OWNER)
            val managerSelector = bindings.selector(WorkflowReferenceTemplateSlots.KNOWLEDGE_MANAGER)
            val securitySelector = bindings.selector(WorkflowReferenceTemplateSlots.KNOWLEDGE_SECURITY_REVIEWER)
            val securityPredicate = bindings.predicate(WorkflowReferenceTemplateSlots.KNOWLEDGE_SECURITY_PREDICATE)
            val revisionMaterial = bindings.providerMaterial(WorkflowReferenceTemplateSlots.KNOWLEDGE_REVISION_SERVICE)
            val owner = human("content-owner-review", "内容负责人审批", ownerSelector, WorkflowApprovalPolicy.one(), evidence, true, true)
            val manager = human("knowledge-manager-review", "知识管理员审批", managerSelector, WorkflowApprovalPolicy.one(), evidence, true, false)
            val security = human("security-review", "安全合规审批", securitySelector, WorkflowApprovalPolicy.all(), evidence, true, false)
            val revision = WorkflowNodeDefinition.serviceTask(
                "subject-revision",
                "补正并创建新版本",
                "必须通过受控 subject adapter 创建不可变新 revision，不能覆盖原版本。",
                revisionMaterial.descriptorDigest,
                revisionMaterial.payloadDigest,
            )
            val nodes = listOf(
                structural("start", WorkflowNodeKind.START, "开始"),
                structural("review-entry", WorkflowNodeKind.EXCLUSIVE_GATEWAY, "审批入口"),
                owner,
                manager,
                structural("security-route", WorkflowNodeKind.EXCLUSIVE_GATEWAY, "安全合规路由"),
                security,
                structural("revision-entry", WorkflowNodeKind.EXCLUSIVE_GATEWAY, "补正入口"),
                revision,
                structural("approved", WorkflowNodeKind.END, "通过"),
                structural("rejected", WorkflowNodeKind.END, "终止驳回"),
            )
            val transitions = listOf(
                edge("knowledge-start-entry", "start", "review-entry"),
                edge("knowledge-revision-return", "subject-revision", "review-entry"),
                edge("knowledge-entry-owner", "review-entry", "content-owner-review"),
                outcome("knowledge-owner-approved", "content-owner-review", "knowledge-manager-review", true),
                outcome("knowledge-owner-rejected", "content-owner-review", "rejected", false),
                outcome("knowledge-manager-approved", "knowledge-manager-review", "security-route", true),
                outcome("knowledge-manager-revision", "knowledge-manager-review", "revision-entry", false),
                conditional("knowledge-security-required", "security-route", "security-review", securityPredicate),
                edge("knowledge-security-default", "security-route", "approved"),
                outcome("knowledge-security-approved", "security-review", "approved", true),
                outcome("knowledge-security-revision", "security-review", "revision-entry", false),
                edge("knowledge-revision-service", "revision-entry", "subject-revision"),
            )
            val providerBinding = WorkflowTemplateProviderNodeBinding.of(
                revision.nodeId,
                WorkflowReferenceTemplateSlots.KNOWLEDGE_REVISION_SERVICE,
                revision.kind,
                revision.descriptorDigest,
                revision.payloadDigest,
                serviceProfile,
            )
            return template(
                "flowweft.knowledge-document",
                tenantId,
                definitionId,
                "flowweft-reference-knowledge-document",
                "知识文件审批参考模板",
                "内容负责人、知识管理员、可选安全合规复核与有界 subject revision cycle。",
                nodes,
                transitions,
                bindings.subject,
                listOf(
                    selectorBinding(owner, 0, WorkflowReferenceTemplateSlots.KNOWLEDGE_CONTENT_OWNER, ownerSelector, organization),
                    selectorBinding(manager, 0, WorkflowReferenceTemplateSlots.KNOWLEDGE_MANAGER, managerSelector, organization),
                    selectorBinding(security, 0, WorkflowReferenceTemplateSlots.KNOWLEDGE_SECURITY_REVIEWER, securitySelector, organization),
                ),
                formBindings(listOf(owner, manager, security), evidence, form),
                listOf(
                    WorkflowTemplatePredicateBinding.of(
                        "knowledge-security-required",
                        WorkflowReferenceTemplateSlots.KNOWLEDGE_SECURITY_PREDICATE,
                        securityPredicate.bindingDigest,
                        predicateProfile,
                    ),
                ),
                listOf(providerBinding),
                sla(listOf(owner, manager, security), calendar, 28_800L, 57_600L, 86_400L),
                listOf(WorkflowTemplateCycleBudget.of("knowledge-revision-cycle", "review-entry", 3)),
                listOf(
                    simulation(
                        "knowledge-standard-approved",
                        choices(
                            "content-owner-review", 1, "knowledge-owner-approved",
                            "knowledge-manager-review", 1, "knowledge-manager-approved",
                            "security-route", 1, "knowledge-security-default",
                        ),
                        "start", "review-entry", "content-owner-review", "knowledge-manager-review",
                        "security-route", "approved",
                    ),
                    simulation(
                        "knowledge-revision-then-approved",
                        choices(
                            "content-owner-review", 1, "knowledge-owner-approved",
                            "knowledge-manager-review", 1, "knowledge-manager-revision",
                            "content-owner-review", 2, "knowledge-owner-approved",
                            "knowledge-manager-review", 2, "knowledge-manager-approved",
                            "security-route", 1, "knowledge-security-default",
                        ),
                        "start", "review-entry", "content-owner-review", "knowledge-manager-review",
                        "revision-entry", "subject-revision", "review-entry", "content-owner-review",
                        "knowledge-manager-review", "security-route", "approved",
                    ),
                ),
            )
        }

        private fun legalTemplate(
            tenantId: String,
            definitionId: String,
            bindings: WorkflowReferenceTemplateBindings,
        ): WorkflowReferenceTemplate {
            val organization = organization(bindings)
            val form = form(bindings)
            val predicateProfile = predicateProfile(bindings)
            val calendar = calendar(bindings)
            val serviceProfile = serviceProfile(bindings)
            val evidence = bindings.evidence(WorkflowReferenceTemplateSlots.LEGAL_EVIDENCE)
            val businessSelector = bindings.selector(WorkflowReferenceTemplateSlots.LEGAL_BUSINESS_OWNER)
            val counselSelector = bindings.selector(WorkflowReferenceTemplateSlots.LEGAL_COUNSEL)
            val expertSelector = bindings.selector(WorkflowReferenceTemplateSlots.LEGAL_EXPERT)
            val leaderSelector = bindings.selector(WorkflowReferenceTemplateSlots.LEGAL_DEPARTMENT_LEADERS)
            val expertPredicate = bindings.predicate(WorkflowReferenceTemplateSlots.LEGAL_EXPERT_PREDICATE)
            val revisionMaterial = bindings.providerMaterial(WorkflowReferenceTemplateSlots.LEGAL_REVISION_SERVICE)
            val business = human("business-owner-review", "业务负责人审批", businessSelector, WorkflowApprovalPolicy.one(), evidence, true, true)
            val expert = human("expert-review", "动态专家审批", expertSelector, WorkflowApprovalPolicy.all(), evidence, true, false)
            val counsel = human("legal-counsel-review", "法务会签", counselSelector, WorkflowApprovalPolicy.quorum(2), evidence, true, false)
            val leaders = human("leader-countersign", "多领导会签", leaderSelector, WorkflowApprovalPolicy.all(), evidence, true, false)
            val revision = WorkflowNodeDefinition.serviceTask(
                "subject-revision",
                "补正并创建新版本",
                "必须保留原法律文件 revision/digest 审批证据并创建新 revision。",
                revisionMaterial.descriptorDigest,
                revisionMaterial.payloadDigest,
            )
            val nodes = listOf(
                structural("start", WorkflowNodeKind.START, "开始"),
                structural("review-entry", WorkflowNodeKind.EXCLUSIVE_GATEWAY, "审批入口"),
                business,
                structural("expert-route", WorkflowNodeKind.EXCLUSIVE_GATEWAY, "专家路由"),
                expert,
                structural("legal-entry", WorkflowNodeKind.EXCLUSIVE_GATEWAY, "法务入口"),
                counsel,
                leaders,
                structural("revision-entry", WorkflowNodeKind.EXCLUSIVE_GATEWAY, "补正入口"),
                revision,
                structural("approved", WorkflowNodeKind.END, "通过"),
                structural("rejected", WorkflowNodeKind.END, "终止驳回"),
            )
            val transitions = listOf(
                edge("legal-start-entry", "start", "review-entry"),
                edge("legal-revision-return", "subject-revision", "review-entry"),
                edge("legal-entry-business", "review-entry", "business-owner-review"),
                outcome("legal-business-approved", "business-owner-review", "expert-route", true),
                outcome("legal-business-rejected", "business-owner-review", "rejected", false),
                conditional("legal-expert-required", "expert-route", "expert-review", expertPredicate),
                edge("legal-expert-default", "expert-route", "legal-entry"),
                outcome("legal-expert-approved", "expert-review", "legal-entry", true),
                outcome("legal-expert-revision", "expert-review", "revision-entry", false),
                edge("legal-entry-counsel", "legal-entry", "legal-counsel-review"),
                outcome("legal-counsel-approved", "legal-counsel-review", "leader-countersign", true),
                outcome("legal-counsel-revision", "legal-counsel-review", "revision-entry", false),
                outcome("legal-leaders-approved", "leader-countersign", "approved", true),
                outcome("legal-leaders-revision", "leader-countersign", "revision-entry", false),
                edge("legal-revision-service", "revision-entry", "subject-revision"),
            )
            return template(
                "flowweft.legal-document",
                tenantId,
                definitionId,
                "flowweft-reference-legal-document",
                "法律文件审批参考模板",
                "业务 owner、风险专家、法务 quorum、多领导全员会签、加签和有界补正回环。",
                nodes,
                transitions,
                bindings.subject,
                listOf(
                    selectorBinding(business, 0, WorkflowReferenceTemplateSlots.LEGAL_BUSINESS_OWNER, businessSelector, organization),
                    selectorBinding(expert, 0, WorkflowReferenceTemplateSlots.LEGAL_EXPERT, expertSelector, organization),
                    selectorBinding(counsel, 0, WorkflowReferenceTemplateSlots.LEGAL_COUNSEL, counselSelector, organization),
                    selectorBinding(leaders, 0, WorkflowReferenceTemplateSlots.LEGAL_DEPARTMENT_LEADERS, leaderSelector, organization),
                ),
                formBindings(listOf(business, expert, counsel, leaders), evidence, form),
                listOf(
                    WorkflowTemplatePredicateBinding.of(
                        "legal-expert-required",
                        WorkflowReferenceTemplateSlots.LEGAL_EXPERT_PREDICATE,
                        expertPredicate.bindingDigest,
                        predicateProfile,
                    ),
                ),
                listOf(
                    WorkflowTemplateProviderNodeBinding.of(
                        revision.nodeId,
                        WorkflowReferenceTemplateSlots.LEGAL_REVISION_SERVICE,
                        revision.kind,
                        revision.descriptorDigest,
                        revision.payloadDigest,
                        serviceProfile,
                    ),
                ),
                sla(listOf(business, expert, counsel, leaders), calendar, 28_800L, 57_600L, 86_400L),
                listOf(WorkflowTemplateCycleBudget.of("legal-revision-cycle", "review-entry", 5)),
                listOf(
                    simulation(
                        "legal-standard-approved",
                        choices(
                            "business-owner-review", 1, "legal-business-approved",
                            "expert-route", 1, "legal-expert-default",
                            "legal-counsel-review", 1, "legal-counsel-approved",
                            "leader-countersign", 1, "legal-leaders-approved",
                        ),
                        "start", "review-entry", "business-owner-review", "expert-route", "legal-entry",
                        "legal-counsel-review", "leader-countersign", "approved",
                    ),
                    simulation(
                        "legal-expert-approved",
                        choices(
                            "business-owner-review", 1, "legal-business-approved",
                            "expert-route", 1, "legal-expert-required",
                            "expert-review", 1, "legal-expert-approved",
                            "legal-counsel-review", 1, "legal-counsel-approved",
                            "leader-countersign", 1, "legal-leaders-approved",
                        ),
                        "start", "review-entry", "business-owner-review", "expert-route", "expert-review",
                        "legal-entry", "legal-counsel-review", "leader-countersign", "approved",
                    ),
                    simulation(
                        "legal-revision-then-approved",
                        choices(
                            "business-owner-review", 1, "legal-business-approved",
                            "expert-route", 1, "legal-expert-default",
                            "legal-counsel-review", 1, "legal-counsel-revision",
                            "business-owner-review", 2, "legal-business-approved",
                            "expert-route", 2, "legal-expert-default",
                            "legal-counsel-review", 2, "legal-counsel-approved",
                            "leader-countersign", 1, "legal-leaders-approved",
                        ),
                        "start", "review-entry", "business-owner-review", "expert-route", "legal-entry",
                        "legal-counsel-review", "revision-entry", "subject-revision", "review-entry",
                        "business-owner-review", "expert-route", "legal-entry", "legal-counsel-review",
                        "leader-countersign", "approved",
                    ),
                ),
            )
        }

        private fun template(
            templateId: String,
            tenantId: String,
            definitionId: String,
            key: String,
            title: String,
            description: String,
            nodes: List<WorkflowNodeDefinition>,
            transitions: List<WorkflowTransitionDefinition>,
            subject: WorkflowTemplateSubjectBinding,
            selectors: List<WorkflowTemplateSelectorBinding>,
            forms: List<WorkflowTemplateFormBinding>,
            predicates: List<WorkflowTemplatePredicateBinding>,
            providers: List<WorkflowTemplateProviderNodeBinding>,
            sla: List<WorkflowTemplateSlaMetadata>,
            cycleBudgets: List<WorkflowTemplateCycleBudget>,
            simulations: List<WorkflowTemplateSimulationCase>,
        ): WorkflowReferenceTemplate {
            val definition = WorkflowDefinition.of(
                tenantId,
                definitionId,
                key,
                TEMPLATE_VERSION,
                1,
                WorkflowDefinitionStatus.DRAFT,
                title,
                description,
                nodes,
                transitions,
            )
            val result = WorkflowReferenceTemplate.create(
                templateId,
                TEMPLATE_VERSION,
                definition,
                subject,
                selectors,
                forms,
                predicates,
                providers,
                sla,
                cycleBudgets,
                simulations,
            )
            val report = WorkflowReferenceTemplateLinter.lint(result)
            require(report.valid) {
                "Workflow reference template is invalid: ${report.issues.joinToString { issue -> issue.code }}"
            }
            return result
        }

        private fun human(
            nodeId: String,
            title: String,
            selector: WorkflowParticipantSelector,
            approval: WorkflowApprovalPolicy,
            evidence: WorkflowHumanTaskEvidenceBinding,
            addSign: Boolean,
            delegation: Boolean,
        ): WorkflowNodeDefinition = WorkflowNodeDefinition.humanTask(
            nodeId,
            title,
            "候选人由固定版本 selector 和当前组织 revision 解析；目录或授权证据缺失时失败关闭。",
            WorkflowHumanTaskPolicy.of(
                listOf(
                    WorkflowHumanTaskParticipantRule.of(
                        selector,
                        approval,
                        WorkflowParticipantMembershipStrategy.CURRENT_MEMBERSHIP,
                    ),
                ),
                WorkflowHumanTaskCapabilities.of(
                    addSignEnabled = addSign,
                    delegationEnabled = delegation,
                    transferEnabled = false,
                    claimEnabled = true,
                ),
                WorkflowSeparationOfDutiesPolicy.of(
                    initiatorExcluded = true,
                    priorApproversExcluded = true,
                ),
                listOf(
                    WorkflowParticipantResolutionStage.ACTIVATION,
                    WorkflowParticipantResolutionStage.QUERY,
                    WorkflowParticipantResolutionStage.CLAIM,
                    WorkflowParticipantResolutionStage.DECISION,
                ),
                evidence,
            ),
        )

        private fun structural(nodeId: String, kind: WorkflowNodeKind, title: String): WorkflowNodeDefinition =
            WorkflowNodeDefinition.of(nodeId, kind, title, null)

        private fun edge(id: String, from: String, to: String): WorkflowTransitionDefinition =
            WorkflowTransitionDefinition.unconditional(id, from, to)

        private fun outcome(id: String, from: String, to: String, approved: Boolean): WorkflowTransitionDefinition =
            WorkflowTransitionDefinition.unconditional(
                id,
                from,
                to,
                if (approved) WorkflowTransitionTrigger.APPROVED else WorkflowTransitionTrigger.REJECTED,
            )

        private fun conditional(
            id: String,
            from: String,
            to: String,
            predicate: WorkflowPredicateRef,
        ): WorkflowTransitionDefinition = WorkflowTransitionDefinition.conditional(id, from, to, predicate)

        private fun selectorBinding(
            node: WorkflowNodeDefinition,
            ruleIndex: Int,
            slot: String,
            selector: WorkflowParticipantSelector,
            profile: WorkflowTemplateProfileRef,
        ): WorkflowTemplateSelectorBinding = WorkflowTemplateSelectorBinding.of(
            node.nodeId,
            ruleIndex,
            slot,
            selector.digest,
            profile,
        )

        private fun formBindings(
            nodes: List<WorkflowNodeDefinition>,
            evidence: WorkflowHumanTaskEvidenceBinding,
            profile: WorkflowTemplateProfileRef,
        ): List<WorkflowTemplateFormBinding> = nodes.map { node ->
            WorkflowTemplateFormBinding.of(node.nodeId, evidence, profile)
        }

        private fun sla(
            nodes: List<WorkflowNodeDefinition>,
            calendar: WorkflowTemplateProfileRef,
            reminder: Long,
            due: Long,
            escalation: Long,
        ): List<WorkflowTemplateSlaMetadata> = nodes.map { node ->
            WorkflowTemplateSlaMetadata.of(node.nodeId, reminder, due, escalation, calendar)
        }

        private fun simulation(
            caseId: String,
            choices: List<WorkflowTemplateSimulationChoice>,
            vararg expectedTrace: String,
        ): WorkflowTemplateSimulationCase = WorkflowTemplateSimulationCase.of(
            caseId,
            choices,
            expectedTrace.toList(),
            64,
        )

        private fun choices(vararg values: Any): List<WorkflowTemplateSimulationChoice> {
            require(values.size % 3 == 0) { "Simulation choice triples are invalid." }
            return values.toList().chunked(3).map { triple ->
                WorkflowTemplateSimulationChoice.of(
                    triple[0] as String,
                    triple[1] as Int,
                    triple[2] as String,
                )
            }
        }

        private fun organization(bindings: WorkflowReferenceTemplateBindings): WorkflowTemplateProfileRef = profile(
            bindings,
            WorkflowReferenceTemplateSlots.ORGANIZATION_PROFILE,
            WorkflowTemplateProfileKind.ORGANIZATION,
        )

        private fun form(bindings: WorkflowReferenceTemplateBindings): WorkflowTemplateProfileRef = profile(
            bindings,
            WorkflowReferenceTemplateSlots.FORM_PROFILE,
            WorkflowTemplateProfileKind.FORM,
        )

        private fun predicateProfile(bindings: WorkflowReferenceTemplateBindings): WorkflowTemplateProfileRef = profile(
            bindings,
            WorkflowReferenceTemplateSlots.PREDICATE_PROFILE,
            WorkflowTemplateProfileKind.PREDICATE,
        )

        private fun calendar(bindings: WorkflowReferenceTemplateBindings): WorkflowTemplateProfileRef = profile(
            bindings,
            WorkflowReferenceTemplateSlots.CALENDAR_PROFILE,
            WorkflowTemplateProfileKind.BUSINESS_CALENDAR,
        )

        private fun serviceProfile(bindings: WorkflowReferenceTemplateBindings): WorkflowTemplateProfileRef = profile(
            bindings,
            WorkflowReferenceTemplateSlots.REVISION_SERVICE_PROFILE,
            WorkflowTemplateProfileKind.SERVICE_NODE,
        )

        private fun profile(
            bindings: WorkflowReferenceTemplateBindings,
            slot: String,
            kind: WorkflowTemplateProfileKind,
        ): WorkflowTemplateProfileRef = bindings.profile(slot, kind)
    }
}
