import yaml

with open('application/src/main/openapi/ledgerflow.yaml', 'r') as f:
    spec = yaml.safe_load(f)

if 'Operator' not in [t['name'] for t in spec.get('tags', [])]:
    spec.setdefault('tags', []).append({'name': 'Operator'})

spec['paths']['/api/v1/operator/failed-operations'] = {
    'get': {
        'tags': ['Operator'],
        'operationId': 'listFailedOperations',
        'summary': 'List failed operations',
        'security': [{'bearerAuth': []}],
        'parameters': [
            {'name': 'limit', 'in': 'query', 'schema': {'type': 'integer', 'minimum': 1, 'maximum': 100, 'default': 20}},
            {'name': 'pageToken', 'in': 'query', 'schema': {'type': 'string'}},
            {'$ref': '#/components/parameters/CorrelationId'}
        ],
        'responses': {
            '200': {
                'description': 'Paginated failed operations',
                'content': {
                    'application/json': {
                        'schema': {'$ref': '#/components/schemas/FailedOperationPage'}
                    }
                }
            }
        }
    }
}

spec['paths']['/api/v1/operator/failed-operations/{operationId}'] = {
    'get': {
        'tags': ['Operator'],
        'operationId': 'getFailedOperation',
        'summary': 'Get failed operation detail and history',
        'security': [{'bearerAuth': []}],
        'parameters': [
            {'name': 'operationId', 'in': 'path', 'required': True, 'schema': {'type': 'string', 'format': 'uuid'}},
            {'$ref': '#/components/parameters/CorrelationId'}
        ],
        'responses': {
            '200': {
                'description': 'Failed operation detail',
                'content': {
                    'application/json': {
                        'schema': {'$ref': '#/components/schemas/FailedOperationDetail'}
                    }
                }
            }
        }
    }
}

spec['paths']['/api/v1/operator/failed-operations/{operationId}/retry'] = {
    'post': {
        'tags': ['Operator'],
        'operationId': 'retryFailedOperation',
        'summary': 'Retry failed operation',
        'security': [{'bearerAuth': []}],
        'parameters': [
            {'name': 'operationId', 'in': 'path', 'required': True, 'schema': {'type': 'string', 'format': 'uuid'}},
            {'$ref': '#/components/parameters/IdempotencyKey'},
            {'$ref': '#/components/parameters/CorrelationId'}
        ],
        'requestBody': {
            'required': True,
            'content': {
                'application/json': {
                    'schema': {'$ref': '#/components/schemas/RetryRequest'}
                }
            }
        },
        'responses': {
            '202': {
                'description': 'Retry command accepted',
                'content': {
                    'application/json': {
                        'schema': {'$ref': '#/components/schemas/RetryResponse'}
                    }
                }
            }
        }
    }
}

spec['paths']['/api/v1/operator/failed-operations/{operationId}/break-glass-retry'] = {
    'post': {
        'tags': ['Operator'],
        'operationId': 'breakGlassRetryFailedOperation',
        'summary': 'Break glass retry failed operation',
        'security': [{'bearerAuth': []}],
        'parameters': [
            {'name': 'operationId', 'in': 'path', 'required': True, 'schema': {'type': 'string', 'format': 'uuid'}},
            {'$ref': '#/components/parameters/IdempotencyKey'},
            {'$ref': '#/components/parameters/CorrelationId'}
        ],
        'requestBody': {
            'required': True,
            'content': {
                'application/json': {
                    'schema': {'$ref': '#/components/schemas/BreakGlassRetryRequest'}
                }
            }
        },
        'responses': {
            '202': {
                'description': 'Break glass retry command accepted',
                'content': {
                    'application/json': {
                        'schema': {'$ref': '#/components/schemas/RetryResponse'}
                    }
                }
            }
        }
    }
}

# Schemas
schemas = spec['components']['schemas']
schemas['FailedOperationSummary'] = {
    'type': 'object',
    'required': ['operationId', 'operationType', 'status', 'failedAt'],
    'properties': {
        'operationId': {'type': 'string', 'format': 'uuid'},
        'operationType': {'type': 'string', 'enum': ['PAYMENT', 'OUTBOX', 'DEAD_LETTER']},
        'status': {'type': 'string'},
        'failureCode': {'type': 'string', 'nullable': True},
        'failedAt': {'type': 'string', 'format': 'date-time'}
    }
}
schemas['FailedOperationPage'] = {
    'type': 'object',
    'required': ['items'],
    'properties': {
        'items': {'type': 'array', 'items': {'$ref': '#/components/schemas/FailedOperationSummary'}},
        'nextPageToken': {'type': 'string', 'nullable': True}
    }
}
schemas['FailedOperationDetail'] = {
    'type': 'object',
    'required': ['summary', 'attempts'],
    'properties': {
        'summary': {'$ref': '#/components/schemas/FailedOperationSummary'},
        'attempts': {
            'type': 'array',
            'items': {
                'type': 'object',
                'required': ['attemptNumber', 'recordedAt', 'outcome'],
                'properties': {
                    'attemptNumber': {'type': 'integer'},
                    'recordedAt': {'type': 'string', 'format': 'date-time'},
                    'outcome': {'type': 'string'},
                    'failureCode': {'type': 'string', 'nullable': True}
                }
            }
        }
    }
}
schemas['RetryRequest'] = {
    'type': 'object',
    'required': ['reason'],
    'properties': {
        'reason': {'type': 'string', 'minLength': 10, 'maxLength': 500}
    }
}
schemas['BreakGlassRetryRequest'] = {
    'type': 'object',
    'required': ['reason', 'approvalReference'],
    'properties': {
        'reason': {'type': 'string', 'minLength': 10, 'maxLength': 500},
        'approvalReference': {'type': 'string', 'minLength': 1, 'maxLength': 100}
    }
}
schemas['RetryResponse'] = {
    'type': 'object',
    'required': ['commandId', 'status', 'acceptedAt'],
    'properties': {
        'commandId': {'type': 'string', 'format': 'uuid'},
        'status': {'type': 'string', 'enum': ['PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED']},
        'acceptedAt': {'type': 'string', 'format': 'date-time'}
    }
}

with open('application/src/main/openapi/ledgerflow.yaml', 'w') as f:
    # Use sort_keys=False to preserve order if possible, though dicts might be unordered in older pythons, in 3.7+ they are ordered.
    yaml.dump(spec, f, sort_keys=False)
