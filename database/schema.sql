
CREATE DATABASE IF NOT EXISTS project_dems;
USE project_dems;
-- 1. USERS
CREATE TABLE users (
    user_id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(150) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(30) NOT NULL DEFAULT 'Viewer',
    phone VARCHAR(20),
    designation VARCHAR(100)
) ENGINE=InnoDB;
-- 2. CASES
CREATE TABLE cases (
    case_id INT AUTO_INCREMENT PRIMARY KEY,
    case_number VARCHAR(50) NOT NULL UNIQUE,
    title VARCHAR(150) NOT NULL,
    description TEXT,
    reported_date DATE,
    status VARCHAR(40) NOT NULL DEFAULT 'Open',
    created_by INT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_cases_creator
        FOREIGN KEY (created_by)
        REFERENCES users(user_id)
        ON DELETE SET NULL
) ENGINE=InnoDB;
-- 3. STORAGE_LOCATION
CREATE TABLE storage_location (
    location_id INT AUTO_INCREMENT PRIMARY KEY,
    room_number VARCHAR(30) NOT NULL,
    cabinet_number VARCHAR(30),
    shelf_number VARCHAR(30),
    locker_number VARCHAR(30)
) ENGINE=InnoDB;
-- 4. TAG
CREATE TABLE tag (
    tag_id INT AUTO_INCREMENT PRIMARY KEY,
    tag_name VARCHAR(50) NOT NULL UNIQUE,
    description VARCHAR(200)
) ENGINE=InnoDB;
-- 5. CASE_ASSIGNMENT
CREATE TABLE case_assignment (
    case_id INT NOT NULL,
    user_id INT NOT NULL,
    assigned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    assigned_role VARCHAR(50),

    PRIMARY KEY (case_id, user_id),

    CONSTRAINT fk_assignment_case
        FOREIGN KEY (case_id)
        REFERENCES cases(case_id)
        ON DELETE CASCADE,

    CONSTRAINT fk_assignment_user
        FOREIGN KEY (user_id)
        REFERENCES users(user_id)
        ON DELETE CASCADE
) ENGINE=InnoDB;
-- 6. EVIDENCE
CREATE TABLE evidence (
    evidence_id INT AUTO_INCREMENT PRIMARY KEY,
    case_id INT NOT NULL,
    item_type VARCHAR(100) NOT NULL,
    description TEXT,
    seized_date DATE,
    item_condition VARCHAR(150),
    photo_path VARCHAR(500),
    status VARCHAR(40) NOT NULL DEFAULT 'In Storage',
    location_id INT,

    CONSTRAINT fk_evidence_case
        FOREIGN KEY (case_id)
        REFERENCES cases(case_id)
        ON DELETE CASCADE,

    CONSTRAINT fk_evidence_location
        FOREIGN KEY (location_id)
        REFERENCES storage_location(location_id)
        ON DELETE SET NULL
) ENGINE=InnoDB;
-- 7. CHAIN_OF_CUSTODY
CREATE TABLE chain_of_custody (
    custody_id INT AUTO_INCREMENT PRIMARY KEY,
    evidence_id INT NOT NULL,
    from_user_id INT,
    to_user_id INT,
    action_type VARCHAR(30) NOT NULL,
    action_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    remarks VARCHAR(255),
    CONSTRAINT fk_custody_evidence
        FOREIGN KEY (evidence_id)
        REFERENCES evidence(evidence_id)
        ON DELETE CASCADE,

    CONSTRAINT fk_custody_from_user
        FOREIGN KEY (from_user_id)
        REFERENCES users(user_id)
        ON DELETE SET NULL,

    CONSTRAINT fk_custody_to_user
        FOREIGN KEY (to_user_id)
        REFERENCES users(user_id)
        ON DELETE SET NULL
) ENGINE=InnoDB;

-- 8. CASE_NOTE
CREATE TABLE case_note (
    note_id INT AUTO_INCREMENT PRIMARY KEY,
    case_id INT NOT NULL,
    author_id INT,
    note_text TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_note_case
        FOREIGN KEY (case_id)
        REFERENCES cases(case_id)
        ON DELETE CASCADE,

    CONSTRAINT fk_note_author
        FOREIGN KEY (author_id)
        REFERENCES users(user_id)
        ON DELETE SET NULL
) ENGINE=InnoDB;

-- 9. EVIDENCE_MOVEMENT
CREATE TABLE evidence_movement (
    movement_id INT AUTO_INCREMENT PRIMARY KEY,
    evidence_id INT NOT NULL,
    from_location_id INT,
    to_location_id INT,
    from_user_id INT,
    to_user_id INT,
    movement_type VARCHAR(30) NOT NULL,
    movement_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reason VARCHAR(255),

    CONSTRAINT fk_movement_evidence
        FOREIGN KEY (evidence_id)
        REFERENCES evidence(evidence_id)
        ON DELETE CASCADE,

    CONSTRAINT fk_movement_from_location
        FOREIGN KEY (from_location_id)
        REFERENCES storage_location(location_id)
        ON DELETE SET NULL,

    CONSTRAINT fk_movement_to_location
        FOREIGN KEY (to_location_id)
        REFERENCES storage_location(location_id)
        ON DELETE SET NULL,

    CONSTRAINT fk_movement_from_user
        FOREIGN KEY (from_user_id)
        REFERENCES users(user_id)
        ON DELETE SET NULL,

    CONSTRAINT fk_movement_to_user
        FOREIGN KEY (to_user_id)
        REFERENCES users(user_id)
        ON DELETE SET NULL
) ENGINE=InnoDB;

-- 10. EVIDENCE_TAG
CREATE TABLE evidence_tag (
    evidence_id INT NOT NULL,
    tag_id INT NOT NULL,
    added_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (evidence_id, tag_id),

    CONSTRAINT fk_evidence_tag_evidence
        FOREIGN KEY (evidence_id)
        REFERENCES evidence(evidence_id)
        ON DELETE CASCADE,

    CONSTRAINT fk_evidence_tag_tag
        FOREIGN KEY (tag_id)
        REFERENCES tag(tag_id)
        ON DELETE CASCADE
) ENGINE=InnoDB;


INSERT INTO users
(name, email, password_hash, role, phone, designation)
VALUES
('Admin User', 'admin@evidence.local',
 '240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9',
 'Admin', '9000000001', 'System Administrator'),

('Rohan Kulkarni', 'rohan@evidence.local',
 '240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9',
 'Officer', '9000000002', 'Inspector'),

('Meera Joshi', 'meera@evidence.local',
 '240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9',
 'Analyst', '9000000003', 'Forensic Analyst');

INSERT INTO storage_location
(room_number, cabinet_number, shelf_number, locker_number)
VALUES
('R-101', 'C-1', 'S-1', 'L-01'),
('R-101', 'C-1', 'S-2', 'L-02'),
('R-102', 'C-4', 'S-1', 'L-11');

INSERT INTO tag (tag_name, description)
VALUES
('Fragile', 'Handle with care'),
('Biological', 'Requires appropriate protective handling'),
('Digital', 'Phones, drives and other devices'),
('High value', 'Requires controlled access');

INSERT INTO cases
(case_number, title, description, reported_date, status, created_by)
VALUES
('CASE-2026-001', 'Warehouse burglary',
 'Break-in reported at the Hadapsar warehouse.',
 '2026-09-14', 'Under Investigation', 1);

INSERT INTO case_assignment
(case_id, user_id, assigned_role)
VALUES
(1, 2, 'Lead'),
(1, 3, 'Forensics');

INSERT INTO evidence
(case_id, item_type, description, seized_date,
 item_condition, status, location_id)
VALUES
(1, 'Mobile phone',
 'Black smartphone found near the rear door.',
 '2026-09-14', 'Screen cracked', 'In Storage', 1);

INSERT INTO chain_of_custody
(evidence_id, from_user_id, to_user_id, action_type, remarks)
VALUES
(1, NULL, 2, 'Collected', 'Seized at scene');

INSERT INTO case_note (case_id, author_id, note_text)
VALUES
(1, 2, 'Scene photographed. Phone bagged and sealed on site.');

INSERT INTO evidence_movement
(evidence_id, from_location_id, to_location_id,
 from_user_id, to_user_id, movement_type, reason)
VALUES
(1, NULL, 1, NULL, 2, 'Stored', 'Initial evidence storage');

INSERT INTO evidence_tag (evidence_id, tag_id)
VALUES
(1, 1),
(1, 3);


SHOW TABLES;

SELECT * FROM users;
SELECT * FROM cases;
SELECT * FROM storage_location;
SELECT * FROM case_assignment;
SELECT * FROM evidence;
SELECT * FROM chain_of_custody;
SELECT * FROM case_note;
SELECT * FROM evidence_movement;
SELECT * FROM tag;
SELECT * FROM evidence_tag;

