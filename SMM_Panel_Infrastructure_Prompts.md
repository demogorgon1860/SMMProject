# Strategic Plan for SMM Panel Infrastructure Recovery – Claude Code (Opus 4.1) Prompts

## 📑 Table of Contents
- [Core Philosophy](#core-philosophy)  
- [Phase 1: Emergency Stabilization](#phase-1-emergency-stabilization)  
  - [1.1 Container Architecture Cleanup](#11-container-architecture-cleanup)  
  - [1.2 Essential Services Recovery](#12-essential-services-recovery)  
  - [1.3 Configuration Rationalization](#13-configuration-rationalization)  
- [Phase 2: Production Readiness](#phase-2-production-readiness)  
  - [2.1 Managed Services Migration Strategy](#21-managed-services-migration-strategy)  
  - [2.2 Kafka Architecture Refinement](#22-kafka-architecture-refinement)  
  - [2.3 Security & Compliance Hardening](#23-security--compliance-hardening)  
- [Phase 3: Operational Excellence](#phase-3-operational-excellence)  
  - [3.1 Monitoring Strategy](#31-monitoring-strategy)  
  - [3.2 Chrome Automation Optimization](#32-chrome-automation-optimization)  
  - [3.3 Business Continuity](#33-business-continuity)  
- [Phase 4: Scale & Optimize](#phase-4-scale--optimize)  
  - [4.1 Performance Optimization](#41-performance-optimization)  
  - [4.2 Cost Optimization](#42-cost-optimization)  
  - [4.3 Developer Experience](#43-developer-experience)  
- [Critical Success Factors](#critical-success-factors)  
  - [Technical Decisions](#technical-decisions)  
  - [Business Alignment](#business-alignment)  
  - [Risk Management](#risk-management)  
- [Anti-Patterns to Avoid](#anti-patterns-to-avoid)  
  - [Architecture Anti-Patterns](#architecture-anti-patterns)  
  - [Operational Anti-Patterns](#operational-anti-patterns)  
  - [Business Anti-Patterns](#business-anti-patterns)  
- [Success Metrics](#success-metrics)  

---

## Core Philosophy
``` 
You are Claude Code (Opus 4.1), act as a world-class infrastructure and architecture strategist. Analyze and explain the following Core Philosophy for SMM Panel infrastructure recovery: 
"Stability over features, simplicity over sophistication, managed over self-hosted."
Perform a structured breakdown of its implications for system design, trade-offs, and how it influences future technical decisions. Provide reasoning step by step, with technical depth, clarity, and production-ready insights.
```

**Migration Principles Prompt:**
```
Now, re-analyze the Core Philosophy through the lens of Migration Principles. 
Explain how rollback capability, resilience, debuggability, and trade-off documentation apply to this philosophy. 
Show how these principles guide every technical decision from the start.
```

---

## Phase 1: Emergency Stabilization

### 1.1 Container Architecture Cleanup
```
Claude Code (Opus 4.1), analyze Phase 1.1: Container Architecture Cleanup. 
Focus on removing orphan containers, eliminating development tools from production, consolidating Chrome nodes, and separating development from production. 
Provide a deep architectural reasoning, step-by-step container lifecycle implications, risks of mismanagement, and practical Docker Compose restructuring strategies. 
Output must be precise, production-grade, and fully aligned with stability-first philosophy.
```

**Migration Principles Prompt:**
```
Re-analyze Container Architecture Cleanup through Migration Principles. 
Explain rollback strategies, debuggability improvements, and how to document trade-offs while stabilizing containers.
```

---

### 1.2 Essential Services Recovery
```
Claude Code (Opus 4.1), analyze Phase 1.2: Essential Services Recovery. 
Evaluate the steps: integrating Spring Boot app as central service, configuring Kafka listener factory, building service dependency chain, and health checks for critical path only. 
Deliver a structured technical explanation of each decision, focusing on message flow integrity, failure modes, recovery strategies, and minimal viable operational reliability. 
Use your strongest reasoning and architecture optimization capabilities.
```

**Migration Principles Prompt:**
```
Re-analyze Essential Services Recovery through Migration Principles. 
Show how rollback, resilience-first design, and debuggability apply to recovering core services.
```

---

### 1.3 Configuration Rationalization
```
Claude Code (Opus 4.1), analyze Phase 1.3: Configuration Rationalization. 
Break down strategies for Kafka consumer concurrency (20% of peak), poll records by processing time, environment-specific configs, and single source of truth for credentials. 
Explain why each decision is critical, what risks are mitigated, and how it directly stabilizes order and payment flow. 
Provide deep technical justifications, configuration-level reasoning, and system-hardening insights.
```

**Migration Principles Prompt:**
```
Re-analyze Configuration Rationalization through Migration Principles. 
Explain rollback of configs, trade-off documentation, and how debuggability is ensured across environments.
```

---

## Phase 2: Production Readiness

### 2.1 Managed Services Migration Strategy
```
Claude Code (Opus 4.1), analyze Phase 2.1: Managed Services Migration Strategy. 
Examine the migration of Kafka → managed MSK/Confluent, PostgreSQL → RDS, Redis → ElastiCache, keeping only app + Chrome self-hosted. 
Deliver a full breakdown of trade-offs, reliability gains, operational simplicity, and hidden costs. 
Explain step-by-step how managed services align with stability, scaling, and compliance. 
Provide detailed migration sequencing with rollback considerations.
```

**Migration Principles Prompt:**
```
Re-analyze Managed Services Migration Strategy through Migration Principles. 
Explain rollback paths, debuggability, and trade-off documentation for each managed service migration.
```

---

### 2.2 Kafka Architecture Refinement
```
Claude Code (Opus 4.1), analyze Phase 2.2: Kafka Architecture Refinement. 
Evaluate separate topics (orders, payments, video-processing, notifications), DLQs, consumer group design by business function, and idempotency keys. 
Provide a technical deep dive into message routing, fault isolation, duplicate prevention, and scaling models. 
Show architectural diagrams in text form and precise sequence flow reasoning. 
Focus on high availability and message durability.
```

**Migration Principles Prompt:**
```
Re-analyze Kafka Architecture Refinement through Migration Principles. 
Explain rollback strategies for topics and consumers, debuggability of DLQs, and documenting trade-offs in scaling models.
```

---

### 2.3 Security & Compliance Hardening
```
Claude Code (Opus 4.1), analyze Phase 2.3: Security & Compliance Hardening. 
Steps: Nginx rate limiting, webhook signing, IP rotation for YouTube, audit trail for finances, encryption at rest. 
Break down each mechanism’s role in resilience, compliance, and fraud prevention. 
Explain trade-offs between performance and compliance, and show step-by-step integration order for maximum impact. 
Provide system-hardening insights at enterprise-grade level.
```

**Migration Principles Prompt:**
```
Re-analyze Security & Compliance Hardening through Migration Principles. 
Show how rollback, resilience-first design, and trade-off documentation apply when adding compliance and security layers.
```

---

## Phase 3: Operational Excellence

### 3.1 Monitoring Strategy
```
Claude Code (Opus 4.1), analyze Phase 3.1: Monitoring Strategy. 
Evaluate Prometheus + Grafana with four golden signals, business dashboards, customer-impact alerts, and JSON structured logging. 
Provide a systematic breakdown of why simplicity > complexity, how to avoid observability anti-patterns, and how metrics link directly to business health. 
Deliver production-grade monitoring blueprint, minimizing overhead but maximizing actionable insights.
```

**Migration Principles Prompt:**
```
Re-analyze Monitoring Strategy through Migration Principles. 
Show rollback approaches for metrics config, how to document trade-offs, and how debuggability is maintained with minimal monitoring stack.
```

---

### 3.2 Chrome Automation Optimization
```
Claude Code (Opus 4.1), analyze Phase 3.2: Chrome Automation Optimization. 
Focus on browser pooling, proxy rotation with health checks, fallback for blocked requests, monitoring YouTube API changes, and fingerprint randomization. 
Break down how each strategy improves stability, reduces bans, and maintains automation efficiency. 
Provide deep reasoning on resource usage, anti-detection, and architectural safeguards against external system volatility.
```

**Migration Principles Prompt:**
```
Re-analyze Chrome Automation Optimization through Migration Principles. 
Show rollback approaches for automation layers, debuggability under bans, and documentation of trade-offs for anti-detection strategies.
```

---

### 3.3 Business Continuity
```
Claude Code (Opus 4.1), analyze Phase 3.3: Business Continuity. 
Evaluate database backup with PITR, runbooks for common issues, circuit breakers, geographic redundancy, and automated customer communication. 
Provide a resilience-first technical deep dive, with structured recovery playbooks and system continuity patterns. 
Show risk scenarios and mitigation strategies step by step, ensuring uninterrupted money flow even under failures.
```

**Migration Principles Prompt:**
```
Re-analyze Business Continuity through Migration Principles. 
Show rollback, debuggability of recovery processes, and documentation of trade-offs in continuity planning.
```

---

## Phase 4: Scale & Optimize

### 4.1 Performance Optimization
```
Claude Code (Opus 4.1), analyze Phase 4.1: Performance Optimization. 
Evaluate caching layers, database indexing, batch ops, async processing, and connection pooling. 
Provide a detailed technical reasoning for each optimization, focusing on handling 10x growth without 10x complexity. 
Explain architecture trade-offs, scaling models, and query-level optimization strategies. 
Deliver production-grade patterns with precise justifications.
```

**Migration Principles Prompt:**
```
Re-analyze Performance Optimization through Migration Principles. 
Show rollback strategies for performance tuning, debuggability under load, and documentation of trade-offs in scaling.
```

---

### 4.2 Cost Optimization
```
Claude Code (Opus 4.1), analyze Phase 4.2: Cost Optimization. 
Evaluate right-sizing services, auto-scaling, spot instances, data retention, and monitoring consolidation. 
Provide a structured financial + technical reasoning, focusing on balancing growth with profitability. 
Deliver step-by-step cost reduction strategies without sacrificing reliability or compliance.
```

**Migration Principles Prompt:**
```
Re-analyze Cost Optimization through Migration Principles. 
Show rollback strategies for scaling policies, debuggability in cost trade-offs, and documenting optimization decisions.
```

---

### 4.3 Developer Experience
```
Claude Code (Opus 4.1), analyze Phase 4.3: Developer Experience. 
Evaluate local dev env with docker-compose override, infra as code, CI/CD pipeline, API docs, and error tracking. 
Provide a deep analysis of developer productivity, reproducibility, and debugging efficiency. 
Explain how strong developer experience directly impacts business scalability and customer satisfaction. 
Deliver practical blueprints for reducing friction in development workflows.
```

**Migration Principles Prompt:**
```
Re-analyze Developer Experience through Migration Principles. 
Show rollback strategies in CI/CD, debuggability improvements in local dev, and documenting trade-offs for developer tooling.
```

---

## Critical Success Factors

### Technical Decisions
```
Claude Code (Opus 4.1), analyze Critical Success Factors: Technical Decisions. 
Evaluate principles like boring technology over bleeding edge, managed services preference, eventual consistency, horizontal scaling, and graceful degradation. 
Provide a structured reasoning on how these principles guide architecture trade-offs, risk minimization, and sustainable scaling. 
Deliver a systematic framework for applying these decisions in real deployments.
```

**Migration Principles Prompt:**
```
Re-analyze Technical Decisions through Migration Principles. 
Show rollback paths, debuggability, and documenting trade-offs when applying these principles.
```

---

### Business Alignment
```
Claude Code (Opus 4.1), analyze Critical Success Factors: Business Alignment. 
Evaluate monitoring business metrics, prioritizing customer-facing features, early compliance, SLAs, and tiered service levels. 
Provide a structured breakdown of how technical architecture aligns with revenue protection, customer trust, and long-term market stability. 
Deliver insights at the intersection of engineering and business outcomes.
```

**Migration Principles Prompt:**
```
Re-analyze Business Alignment through Migration Principles. 
Show rollback and debuggability applied to SLAs, compliance, and business-aligned decisions.
```

---

### Risk Management
```
Claude Code (Opus 4.1), analyze Critical Success Factors: Risk Management. 
Evaluate risks: YouTube API changes, payment freezes, traffic spikes, redundancy, and legal separation. 
Deliver a structured risk matrix with step-by-step mitigation strategies. 
Provide technical + business-level reasoning for resilience against unpredictable external threats. 
Focus on protecting money flow and compliance at all costs.
```

**Migration Principles Prompt:**
```
Re-analyze Risk Management through Migration Principles. 
Show rollback strategies in risk handling, debuggability in failure events, and documentation of trade-offs in mitigation.
```

---

## Anti-Patterns to Avoid

### Architecture Anti-Patterns
```
Claude Code (Opus 4.1), analyze Architecture Anti-Patterns. 
Evaluate pitfalls: dev tools in production, shared thread pools, mixed sync/async, tight coupling, ignoring health checks. 
Provide detailed explanations why each is dangerous, real-world failure scenarios, and precise prevention strategies. 
Deliver an anti-pattern reference sheet for system architects.
```

**Migration Principles Prompt:**
```
Re-analyze Architecture Anti-Patterns through Migration Principles. 
Show rollback guidance, debuggability improvement, and documenting trade-offs when preventing these pitfalls.
```

---

### Operational Anti-Patterns
```
Claude Code (Opus 4.1), analyze Operational Anti-Patterns. 
Evaluate debugging in production, manual deployments, skipping load tests, single points of failure, and ignoring <1% error rates. 
Break down why each practice undermines system stability and customer trust. 
Provide structured countermeasures and automation-first alternatives.
```

**Migration Principles Prompt:**
```
Re-analyze Operational Anti-Patterns through Migration Principles. 
Show rollback options, debuggability, and documenting trade-offs in operational practices.
```

---

### Business Anti-Patterns
```
Claude Code (Opus 4.1), analyze Business Anti-Patterns. 
Evaluate risks of over-promising, unnecessary sensitive data storage, payments without idempotency, hard-coded rules, and ignoring complaint patterns. 
Provide structured reasoning why these destroy scalability and compliance. 
Deliver practical prevention guidelines for business-aligned engineering.
```

**Migration Principles Prompt:**
```
Re-analyze Business Anti-Patterns through Migration Principles. 
Show rollback and debuggability implications when avoiding harmful business practices.
```

---

## Success Metrics
```
Claude Code (Opus 4.1), analyze Success Metrics. 
Evaluate technical health metrics (API response <200ms, Kafka lag <1000, 99% order success, 99.5% uptime, 85% Chrome automation success) 
and business health metrics (SLA fulfillment, >95% payment success, <2% complaints, <0.5% chargebacks, >10% MRR growth). 
Provide a structured reasoning on why each is critical, how to measure accurately, and how to tie metrics directly to engineering decisions. 
Deliver a production-grade metrics framework.
```

**Migration Principles Prompt:**
```
Re-analyze Success Metrics through Migration Principles. 
Show rollback strategies in metric thresholds, debuggability in monitoring, and documenting trade-offs in metric definitions.
```
