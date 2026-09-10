# Joint CP Effective Template Staff Endpoint Check

Date: 10 Sep 2026

## Mobile endpoints already used

- `GET /api/hr/staff?status=active`
- `GET /api/hr/staff/active`
- `GET /api/hr/staff/search?query=<query>&lite=1`

No new picker endpoint is required. Every staff row returned by these endpoints must include the effective IAM template metadata used by the Joint CP create API:

```json
{
  "id": "staff-id",
  "name": "Staff name",
  "iamTemplateId": "template-id",
  "iamTemplateName": "Sales and Marketing 3",
  "iamTemplateLevel": 3,
  "jointCpWorkflowRole": "outcome_owner"
}
```

The backend must resolve these effective values using the same precedence as Joint CP creation: staff-level IAM template first, then the designation-linked IAM template. Raw staff fields alone are insufficient when the assignment is inherited from the designation.

## Required create validation

The Joint CP create endpoint must resolve both staff records again and reject the request before creating anything when:

- either effective template is missing: `TEMPLATE_REQUIRED`
- both effective template IDs are the same: `SAME_TEMPLATE_NOT_ALLOWED`
- either effective template level is missing: `TEMPLATE_LEVEL_REQUIRED`
- both effective template levels are equal: `SAME_TEMPLATE_LEVEL_NOT_ALLOWED`
- the resolved workflow roles are not one outcome owner and one reviewer: `INVALID_JOINT_CP_ROLE_PAIR`

The client now performs the same checks for fast feedback, but server validation is mandatory because client picker data can be stale.

## Acceptance checks

1. Staff using the same template cannot be selected together.
2. Staff using different template IDs with the same numeric level cannot be selected together.
3. Missing template metadata shows a warning and blocks creation.
4. One lower-level and one higher-level staff member can be selected in either picker order.
5. The create response snapshots the resolved outcome owner and reviewer IDs.
6. A rejected request creates no CP visit or partial assignment.
