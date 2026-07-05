# Unlimited Concept Tree Design

## Goal

BuddyStudy should store topic coverage as an unlimited-depth concept tree instead of a flat one-level concept list. Question generation should target the next least-covered leaf concept and include the full concept path in the prompt.

## Current State

The backend currently stores:

- `study_question_concepts`: one flat concept row per study.
- `study_question_coverage`: angle coverage rows linked to a concept.
- `questions`: selected `concept_id`, `concept_key`, and `angle_key`.

The current OpenAI coverage blueprint asks for 8 to 12 concepts with angles. There is no parent concept, depth, path, or leaf marker.

## Data Model

Add tree metadata to `study_question_concepts`:

- `parent_concept_id`: nullable self-reference. Root concepts have `null`.
- `depth`: non-negative integer. Root depth is `0`; children are parent depth plus one.
- `path`: stable slash-separated key path such as `persistence/aof/recovery`.
- `concept_path`: human-readable path such as `Persistence > AOF > Recovery`.
- `leaf`: boolean marker. Only leaf concepts get coverage angle rows.

Keep `study_question_coverage` as the coverage unit. It continues to track one angle under one concept. The difference is that `concept_id` should now point to a leaf concept.

Existing flat concepts are compatible after migration:

- `parent_concept_id = null`
- `depth = 0`
- `path = concept_key`
- `concept_path = concept_name`
- `leaf = true`

## OpenAI Blueprint Contract

Coverage blueprint generation should accept recursive concept JSON:

```json
{
  "concepts": [
    {
      "key": "persistence",
      "name": "Persistence",
      "children": [
        {
          "key": "aof",
          "name": "AOF",
          "children": [
            {
              "key": "recovery",
              "name": "Recovery",
              "angles": [
                { "key": "failure_mode", "name": "Failure Mode" }
              ]
            }
          ]
        }
      ]
    }
  ]
}
```

Concepts may have `children`, `angles`, both, or neither. The storage rules are:

- Every concept in the tree is stored.
- A concept is a coverage leaf when it has no children.
- Angles attached to non-leaf concepts are ignored for coverage selection.
- A leaf without angles receives one fallback angle: `general` / `General`.
- Empty or invalid blueprint responses fall back to the existing single-topic concept with default angles.

The parser must be recursive and must not enforce a maximum depth.

## Selection And Prompting

Coverage selection remains least-covered first:

1. lowest `asked_count`
2. concepts never asked before
3. oldest `last_asked_at`
4. smallest coverage row id

The selected `QuestionCoverageSelection` should include:

- leaf concept id/key/name
- full concept key path
- full concept display path
- angle key/name

Question generation should include both the selected leaf and the full path:

```text
Focus concept path: Persistence > AOF > Recovery
Focus concept: Recovery
Question angle: Failure Mode
```

Questions continue to store `concept_id`, `concept_key`, and `angle_key`. `concept_key` should remain the selected leaf key for compatibility. The full path is available through the coverage/concept tables.

## Scope

This change is backend-only. iOS UI, record payloads, and public API response shapes do not need to change for this first step.

## Testing

Add focused tests for:

- Recursive OpenAI blueprint parsing.
- Persistence of nested concepts with parent/depth/path/leaf fields.
- Coverage rows only for leaf concepts.
- Selection returns full path metadata.
- Question prompt includes full concept path.

Existing flat blueprint behavior should remain valid.
