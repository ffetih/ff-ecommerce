-- The inbox forgets event ids after its retention (ADR-0005); the hourly cleanup finds them by age.
CREATE INDEX inbox_messages_processed_at ON inbox_messages (processed_at);
