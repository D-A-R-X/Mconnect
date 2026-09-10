# Joint CP Template Level Projection API Correction

Date: 10 Sep 2026

## Observed mobile response condition

The mobile warning `Joint CP template level is missing for one of these staff`
can occur only when both selected staff rows contain template IDs but at least
one row omits `iamTemplateLevel`.

This means assigning two different templates is not enough for the current
picker response. The staff endpoint must also project the effective numeric
level, including designation-inherited templates and the documented numeric
designation fallback.

## Existing endpoints to correct

- `GET /api/hr/staff?status=active`
- `GET /api/hr/staff/active`
- `GET /api/hr/staff/search?query=<query>&lite=1`

Every row must use the same effective-template resolver as Joint CP creation:

```json
{
  "_id": "staff-id",
  "name": "Staff name",
  "iamTemplateId": "effective-template-id",
  "iamTemplateName": "Effective template name",
  "iamTemplateLevel": 3,
  "jointCpWorkflowRole": "outcome_owner"
}
```

Do not return only the raw `staff.iamTemplateId`. Resolve in this order:

1. staff-level IAM template;
2. designation-linked IAM template;
3. documented numeric designation-level fallback.

## Authoritative creation validation

`POST /api/marketing/clientPlaceVisits/create` remains the authority. Mobile
sends staff IDs only. The API must resolve both effective templates and reject
same template, equal level, missing authority, or an invalid owner/reviewer pair
before any CP or field-visit row is created.

Mobile now blocks only conflicts proven by complete picker metadata. Missing
picker metadata is deferred to this API so a projection omission cannot falsely
reject two valid, differently assigned staff members.

## Acceptance checks

1. Two different effective templates with different resolved levels create.
2. Same effective template returns `SAME_TEMPLATE_NOT_ALLOWED`.
3. Different template IDs with equal levels return `SAME_TEMPLATE_LEVEL_NOT_ALLOWED`.
4. Missing effective authority returns a structured configuration error.
5. Rejected requests create no partial CP, assignment, or field visit.
