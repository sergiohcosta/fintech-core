-- cleanup.sql — limpa dados entre testes de integração
-- A ordem respeita as FKs (filhas antes de pais)
DELETE FROM transactions;
DELETE FROM installment_groups;
DELETE FROM invoices;
DELETE FROM invitations;
DELETE FROM credit_card_details;
DELETE FROM accounts;
DELETE FROM categories;
DELETE FROM users;
DELETE FROM tenants;
