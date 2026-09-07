# The honest arm, 2026-09-06

A live benchmark of memory learned **without the oracle**: the learner saw one profit per training
scenario - the action it chose - and never the other three.

## Setup

Fourth instance, `snowball-blind`, seeded with 120 lessons from a blind UCB1 learner run over all
250 training scenarios in order (the other 70 buckets never reached four observations). Same keys,
same cascade, same prompt, same model and the same 50 held-out scenarios as the two published arms.
Learning off during the measurement. Log: `benchmark-blind.log`.

## Result

| memory | optimal | total regret | memory answered |
|---|---|---|---|
| none | 44% | 44.04 | - |
| oracle, cascade | 80% | 7.18 | 50/50 |
| **blind, observed action only** | **52%** | **59.89** | 50/50 |

Blind memory hits the best action a little more often than no memory and **loses more money than
no memory at all**. Paired: against the clean arm, better on 18 and worse on 13 (p = 0.47, not
significant); against the oracle arm, worse on 21 and better on 7 (p = 0.0125).

## Where the money went

The blind arm chose 30% in 15 scenarios; the clean arm never did, nor did the oracle arm. Nearly
every expensive mistake is a 30% on ice cream or soft drinks, where the best action is 20%:

| product | scenarios | blind regret | clean regret |
|---|---|---|---|
| ICE500 | 10 | 31.72 | 17.37 |
| COLA15 | 11 | 14.29 | 5.60 |
| CHIPS1 | 6 | 10.06 | 11.46 |
| BEER6 | 6 | 2.20 | 1.76 |
| YOG500 | 8 | 1.62 | 3.88 |
| MEAT1 | 9 | 0.00 | 3.97 |

The cause is structural, not bad luck. A blind learner observes one action per scenario, and which
action it observes depends on what it chose. Trying 30% selectively - in the contexts where it
looked promising - gives the general ice-cream bucket a high observed mean for 30%, not because 30%
is best but because 30% was sampled where it was least bad. Aggregating such observations across
contexts is selection bias by construction; the oracle learner has none of it because it sees all
four actions in every context. The model follows a lesson literally, so a confident wrong lesson
costs more than no lesson.

Where one action dominates regardless of context - meat, yogurt - blind memory helps.

## What the offline estimate got wrong

The offline experiment predicted blind memory at roughly half the loss of "no memory". Its "no
memory" baseline was a fixed 0% policy (97.27 on the validation slice). The real no-memory arm is
the model, which is a competent policy on its own (44.04 on the held-out set). Against a fixed
policy the blind memory looks like a gain; against the model it is a loss. The offline baseline
was the wrong comparator, and only the live run exposed it.

## What this establishes

- The published delta (44% -> 80%, 44.04 -> 7.18) depends on the oracle. Remove it and, at this
  data volume and with this learner, the memory does not beat the model on its own.
- The failure mode is known in the literature - self-reinforcing error from selection-biased
  observations - and here it is measured in money, on the same scenarios as the headline number.
- Fixing it is off-policy learning proper: propensity-weighted estimates, or exploration on a
  schedule that does not depend on the estimate. Neither fits in the remaining time; both are the
  right next step.
