-- shipping-service: own database and user (ADR-0002). Local development credentials only.
CREATE USER shipping_service WITH PASSWORD 'shipping_service';
CREATE DATABASE shipping_db OWNER shipping_service;
