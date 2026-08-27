# High-Level Architecture

This diagram shows the logical service boundaries, persistent storage, and the main learning and benchmark flows. The MVP can still run as one application process; these are responsibility boundaries, not required deployment boundaries.

```mermaid
flowchart LR
    subgraph CORE["Core Promotion Learning Loop"]
        SG["Scenario Generator (Service)"]
        PA(["Promotion Agent (AI Agent)"])
        MS["Market Simulator (Service)"]
        EL(["Evaluator / Learner (AI + Deterministic Logic)"])

        SG -->|PromotionScenario| PA
        PA -->|PromotionDecision| MS
        SG -->|Scenario context| MS
        MS -->|PromotionOutcome| EL
        SG -->|Same scenario| EL
        PA -->|Chosen action and reason| EL
        EL -.->|Counterfactual replay: 0%, 10%, 20%, 30%| MS
    end

    subgraph MEMORY["Persistent Memory"]
        XM["xmemory (Memory Service)"]
        CASES[("Promotion Cases")]
        LESSONS[("Lessons")]

        XM --> CASES
        XM --> LESSONS
    end

    subgraph EVAL["Evaluation"]
        BR["Benchmark Runner (Evaluation Service)"]
        METRICS["Metrics: optimal action rate, average regret, gross profit, memory retrieval hit rate"]

        BR --> METRICS
    end

    PA -->|Read relevant experience| XM
    XM -->|Relevant lessons| PA
    EL -->|Write completed case and reusable lesson| XM

    BR -.->|Run fixed scenario suite| SG
    BR -.->|Baseline: clean memory / Learned: trained memory| XM
    EL -.->|Regret and outcome data| METRICS
```

## Reading the diagram

- **Scenario Generator** owns scenario construction, not promotion decisions.
- **Promotion Agent** reads xmemory before choosing one allowed discount.
- **Market Simulator** is the hidden external world and produces objective outcomes.
- **Evaluator / Learner** computes counterfactual regret and turns results into durable experience.
- **xmemory** persists completed cases and reusable lessons across runs.
- **Benchmark Runner** holds the model, prompt, simulator, and test scenarios constant while comparing clean versus trained memory.

The important feedback path is:

`Scenario -> Memory Read -> Decision -> Simulation -> Evaluation -> Memory Write -> Next Scenario`
