-- An unexpanded node remains expandable unless an explicit curriculum leaf says otherwise.
ALTER TABLE studies ADD COLUMN curriculum_terminal BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE system_topic_catalog ADD COLUMN curriculum_terminal BOOLEAN NOT NULL DEFAULT FALSE;

-- Preserve legacy trees. Only nodes at (or beyond) the existing four-descendant limit
-- become terminal; do not infer terminal status from the absence of children.
UPDATE studies AS node
JOIN studies AS parent1 ON parent1.id = node.parent_study_id AND parent1.user_id = node.user_id
JOIN studies AS parent2 ON parent2.id = parent1.parent_study_id AND parent2.user_id = node.user_id
JOIN studies AS parent3 ON parent3.id = parent2.parent_study_id AND parent3.user_id = node.user_id
JOIN studies AS parent4 ON parent4.id = parent3.parent_study_id AND parent4.user_id = node.user_id
SET node.curriculum_terminal = TRUE;

UPDATE system_topic_catalog SET curriculum_terminal = TRUE WHERE depth >= 4;
