-- payment-service: own database and user (ADR-0002). Local development credentials only.
CREATE USER payment_service WITH PASSWORD 'payment_service';
CREATE DATABASE payment_db OWNER payment_service;
