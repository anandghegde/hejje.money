-- Plan M9.3: in hejje.news.classifier=shadow, Jev's assessment is stored beside the LLM's with shadow=true. Shadow rows
-- never feed the bias; GET /news/classifier-comparison reads both.
ALTER TABLE news_assessment ADD COLUMN shadow boolean NOT NULL DEFAULT false;
