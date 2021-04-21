use serde_json::{json, Value};

pub const INDEX_NAME: &str = "catalog";

pub fn schema() -> Value {
    json!({
        "mappings" : {
            "properties" : {
                "id" : {
                    "type" : "keyword"
                },
                "title" : {
                    "type" : "text",
                    "fields": {
                        "raw": {
                            "type": "keyword"
                        },
                        "english": {
                            "type": "text",
                            "analyzer": "english"
                        }
                    }
                },
                "tagline": {
                    "type": "text",
                    "fields": {
                        "english": {
                            "type": "text",
                            "analyzer": "english"
                        }
                    }
                },
                "overview": {
                    "type": "text",
                    "fields": {
                        "english": {
                            "type": "text",
                            "analyzer": "english"
                        }
                    }
                },
                "release_date": {
                    "type": "date",
                    "format": "yyyy-MM-dd||epoch_millis"
                },
                "spoken_language": {
                    "properties": {
                        "code": {
                            "type": "keyword"
                        },
                        "name": {
                            "type": "text",
                            "fields": {
                                "raw": {
                                    "type": "keyword"
                                }
                            }
                        }
                    }
                },
                "production_country": {
                    "properties": {
                        "code": {
                            "type": "keyword"
                        },
                        "name": {
                            "type": "text",
                            "fields": {
                                "raw": {
                                    "type": "keyword"
                                }
                            }
                        }
                    }
                },
                "genre": {
                    "properties": {
                        "id": {
                            "type": "keyword"
                        },
                        "name": {
                            "type": "text",
                            "fields": {
                                "raw": {
                                    "type": "keyword"
                                }
                            }
                        }
                    }
                },
                "created": {
                    "type": "date"
                },
                "updated": {
                    "type": "date"
                },
                "indexed": {
                    "type": "date"
                }
            }
        }
    })
}