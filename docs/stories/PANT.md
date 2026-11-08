# Pantry stories

Source of truth: `docs/SPEC.md` §4.3. Offline lines are §6.5 (Pantry row). Module-off lines are §9 (rule X7 and the matrix). One story per feature ID. Where the feature is more than one of API, web, iOS, and Android, it is split with the suffix `-api`, `-web`, `-ios`, or `-android`. `PANT-14` stays unsuffixed because it is server-only. Order inside a feature is API, then web, then iOS, then Android.

An open question is unresolved in the spec. Do not pick an answer while implementing the story.

## PANT-01-web — Pantry locations

**User value:** The household can keep food in the fridge, the freezer, the pantry, or a place they add themselves.

**Acceptance criteria:**
- [ ] The user can create, read, update, and delete a location.
- [ ] A location can be fridge, freezer, pantry, or custom (kind `other`).

**Offline:** §6.5 Pantry: readable offline, and every write works offline and is queued until sync.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `CORE-02-web`, `CORE-05-web`, `SHOP-08-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

## PANT-01-ios — Pantry locations

**User value:** The household can keep food in the fridge, the freezer, the pantry, or a place they add themselves.

**Acceptance criteria:**
- [ ] The user can create, read, update, and delete a location.
- [ ] A location can be fridge, freezer, pantry, or custom (kind `other`).

**Offline:** §6.5 Pantry: readable offline, and every write works offline and is queued until sync.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `CORE-02-ios`, `CORE-05-ios`, `SHOP-08-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

## PANT-01-android — Pantry locations

**User value:** The household can keep food in the fridge, the freezer, the pantry, or a place they add themselves.

**Acceptance criteria:**
- [ ] The user can create, read, update, and delete a location.
- [ ] A location can be fridge, freezer, pantry, or custom (kind `other`).

**Offline:** §6.5 Pantry: readable offline, and every write works offline and is queued until sync.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `CORE-02-android`, `CORE-05-android`, `SHOP-08-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

## PANT-02-web — Pantry items

**User value:** The household can record what it has, where it is, and when it expires.

**Acceptance criteria:**
- [ ] The user can create, read, update, and delete an item.
- [ ] The item has name, quantity, unit, location, category, added date, expiry, and note.

**Offline:** §6.5 Pantry: readable offline, and every write works offline and is queued until sync.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `PANT-01-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

**Open question:** §5 says clients never write `pantry_item.quantity`; a movement delta is the only change. §4 lists quantity inside item CRUD. Is the starting quantity an `added` movement (PANT-03)?

## PANT-02-ios — Pantry items

**User value:** The household can record what it has, where it is, and when it expires.

**Acceptance criteria:**
- [ ] The user can create, read, update, and delete an item.
- [ ] The item has name, quantity, unit, location, category, added date, expiry, and note.

**Offline:** §6.5 Pantry: readable offline, and every write works offline and is queued until sync.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `PANT-01-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

**Open question:** §5 says clients never write `pantry_item.quantity`; a movement delta is the only change. §4 lists quantity inside item CRUD. Is the starting quantity an `added` movement (PANT-03)?

## PANT-02-android — Pantry items

**User value:** The household can record what it has, where it is, and when it expires.

**Acceptance criteria:**
- [ ] The user can create, read, update, and delete an item.
- [ ] The item has name, quantity, unit, location, category, added date, expiry, and note.

**Offline:** §6.5 Pantry: readable offline, and every write works offline and is queued until sync.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `PANT-01-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

**Open question:** §5 says clients never write `pantry_item.quantity`; a movement delta is the only change. §4 lists quantity inside item CRUD. Is the starting quantity an `added` movement (PANT-03)?

## PANT-03-api — Apply a quantity delta

**User value:** Two people can change the same jar while offline and both changes count, instead of one wiping the other out.

**Acceptance criteria:**
- [ ] Inserting a `pantry_movement` applies its delta to `pantry_item.quantity` (X5).
- [ ] That trigger is the only way quantity changes.
- [ ] A sync `delta` mutation inserts the movement rather than writing quantity (§6.2).

**Offline:** §6.5 Pantry: writes are done offline and must still apply when the client pushes them later.

**Module off:** Pantry off (X7): the movement mutation is rejected with `module_disabled`, so the trigger does not apply a new delta.

**Dependencies:** `SHOP-08-api`

**Skill:** `.claude/skills/node-backend/SKILL.md`; `.claude/skills/sql-supabase/SKILL.md`

## PANT-03-web — Quick plus and minus

**User value:** From the list, a person can add or remove one without opening the item.

**Acceptance criteria:**
- [ ] Plus and minus are available on the list view.
- [ ] Each tap writes a `pantry_movement` delta, not a direct quantity edit.

**Offline:** §6.5 Pantry: readable offline, and every write works offline and is queued until sync.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `PANT-02-web`, `PANT-03-api`

**Skill:** `.claude/skills/react-web/SKILL.md`

## PANT-03-ios — Quick plus and minus

**User value:** From the list, a person can add or remove one without opening the item.

**Acceptance criteria:**
- [ ] Plus and minus are available on the list view.
- [ ] Each tap writes a `pantry_movement` delta, not a direct quantity edit.

**Offline:** §6.5 Pantry: readable offline, and every write works offline and is queued until sync.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `PANT-02-ios`, `PANT-03-api`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

## PANT-03-android — Quick plus and minus

**User value:** From the list, a person can add or remove one without opening the item.

**Acceptance criteria:**
- [ ] Plus and minus are available on the list view.
- [ ] Each tap writes a `pantry_movement` delta, not a direct quantity edit.

**Offline:** §6.5 Pantry: readable offline, and every write works offline and is queued until sync.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `PANT-02-android`, `PANT-03-api`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

## PANT-04-web — Low-stock threshold and auto-add

**User value:** Each item can say how low is too low, and whether that should feed the shopping list.

**Acceptance criteria:**
- [ ] Each item has a low-stock threshold.
- [ ] Each item has an auto-add toggle.

**Offline:** §6.5 Pantry: readable offline, and every write works offline and is queued until sync.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error. With shopping off, the toggle is still stored, and X1 does not create a list line (see PANT-14).

**Dependencies:** `PANT-02-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

## PANT-04-ios — Low-stock threshold and auto-add

**User value:** Each item can say how low is too low, and whether that should feed the shopping list.

**Acceptance criteria:**
- [ ] Each item has a low-stock threshold.
- [ ] Each item has an auto-add toggle.

**Offline:** §6.5 Pantry: readable offline, and every write works offline and is queued until sync.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error. With shopping off, the toggle is still stored, and X1 does not create a list line (see PANT-14).

**Dependencies:** `PANT-02-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

## PANT-04-android — Low-stock threshold and auto-add

**User value:** Each item can say how low is too low, and whether that should feed the shopping list.

**Acceptance criteria:**
- [ ] Each item has a low-stock threshold.
- [ ] Each item has an auto-add toggle.

**Offline:** §6.5 Pantry: readable offline, and every write works offline and is queued until sync.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error. With shopping off, the toggle is still stored, and X1 does not create a list line (see PANT-14).

**Dependencies:** `PANT-02-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

## PANT-05-web — Eat soon

**User value:** The household can see the food that expires within the next seven days.

**Acceptance criteria:**
- [ ] The view lists items expiring within 7 days.
- [ ] The list is sorted.

**Offline:** §6.5 Pantry: readable offline, and every write works offline and is queued until sync.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `PANT-02-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

**Open question:** §4 says the view is sorted and does not say by which field. §10's eat-soon notice uses 3 days, which is PANT-16, not this 7-day view.

## PANT-05-ios — Eat soon

**User value:** The household can see the food that expires within the next seven days.

**Acceptance criteria:**
- [ ] The view lists items expiring within 7 days.
- [ ] The list is sorted.

**Offline:** §6.5 Pantry: readable offline, and every write works offline and is queued until sync.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `PANT-02-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

**Open question:** §4 says the view is sorted and does not say by which field. §10's eat-soon notice uses 3 days, which is PANT-16, not this 7-day view.

## PANT-05-android — Eat soon

**User value:** The household can see the food that expires within the next seven days.

**Acceptance criteria:**
- [ ] The view lists items expiring within 7 days.
- [ ] The list is sorted.

**Offline:** §6.5 Pantry: readable offline, and every write works offline and is queued until sync.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `PANT-02-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

**Open question:** §4 says the view is sorted and does not say by which field. §10's eat-soon notice uses 3 days, which is PANT-16, not this 7-day view.

## PANT-06-web — Search and filter pantry

**User value:** A person can find an item by search and narrow it to a location or a category, on the device.

**Acceptance criteria:**
- [ ] The user can search items.
- [ ] The user can filter by location.
- [ ] The user can filter by category.
- [ ] Search and filter run on the local database.

**Offline:** §6.5 Pantry: readable offline, and every write works offline and is queued until sync. §4 says this search is local, so it works with no network.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `PANT-02-web`, `PANT-01-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

## PANT-06-ios — Search and filter pantry

**User value:** A person can find an item by search and narrow it to a location or a category, on the device.

**Acceptance criteria:**
- [ ] The user can search items.
- [ ] The user can filter by location.
- [ ] The user can filter by category.
- [ ] Search and filter run on the local database.

**Offline:** §6.5 Pantry: readable offline, and every write works offline and is queued until sync. §4 says this search is local, so it works with no network.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `PANT-02-ios`, `PANT-01-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

## PANT-06-android — Search and filter pantry

**User value:** A person can find an item by search and narrow it to a location or a category, on the device.

**Acceptance criteria:**
- [ ] The user can search items.
- [ ] The user can filter by location.
- [ ] The user can filter by category.
- [ ] Search and filter run on the local database.

**Offline:** §6.5 Pantry: readable offline, and every write works offline and is queued until sync. §4 says this search is local, so it works with no network.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `PANT-02-android`, `PANT-01-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

## PANT-07-web — Usage history

**User value:** A person can open an item and see how its quantity has moved.

**Acceptance criteria:**
- [ ] An item shows its `pantry_movement` history.

**Offline:** §6.5 Pantry: readable offline, and every write works offline and is queued until sync.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `PANT-03-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

## PANT-07-ios — Usage history

**User value:** A person can open an item and see how its quantity has moved.

**Acceptance criteria:**
- [ ] An item shows its `pantry_movement` history.

**Offline:** §6.5 Pantry: readable offline, and every write works offline and is queued until sync.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `PANT-03-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

## PANT-07-android — Usage history

**User value:** A person can open an item and see how its quantity has moved.

**Acceptance criteria:**
- [ ] An item shows its `pantry_movement` history.

**Offline:** §6.5 Pantry: readable offline, and every write works offline and is queued until sync.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `PANT-03-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

## PANT-08-web — Categories with default shelf life

**User value:** Picking a category while adding food suggests an expiry from that category's usual shelf life.

**Acceptance criteria:**
- [ ] The user can keep categories, each with a default shelf life.
- [ ] Adding an item suggests an expiry from the chosen category's default shelf life.

**Offline:** §6.5 Pantry: readable offline, and every write works offline and is queued until sync.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `PANT-02-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

**Open question:** §12's seed file is described as holding global categories, and §8 `Category` is household-scoped. Are the starting categories copied into the household, or is there a global catalogue?

## PANT-08-ios — Categories with default shelf life

**User value:** Picking a category while adding food suggests an expiry from that category's usual shelf life.

**Acceptance criteria:**
- [ ] The user can keep categories, each with a default shelf life.
- [ ] Adding an item suggests an expiry from the chosen category's default shelf life.

**Offline:** §6.5 Pantry: readable offline, and every write works offline and is queued until sync.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `PANT-02-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

**Open question:** §12's seed file is described as holding global categories, and §8 `Category` is household-scoped. Are the starting categories copied into the household, or is there a global catalogue?

## PANT-08-android — Categories with default shelf life

**User value:** Picking a category while adding food suggests an expiry from that category's usual shelf life.

**Acceptance criteria:**
- [ ] The user can keep categories, each with a default shelf life.
- [ ] Adding an item suggests an expiry from the chosen category's default shelf life.

**Offline:** §6.5 Pantry: readable offline, and every write works offline and is queued until sync.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `PANT-02-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

**Open question:** §12's seed file is described as holding global categories, and §8 `Category` is household-scoped. Are the starting categories copied into the household, or is there a global catalogue?

## PANT-09-web — Barcode scan for a pantry item

**User value:** A person can scan a barcode and file that product in the pantry.

**Acceptance criteria:**
- [ ] The camera scans a barcode.
- [ ] The client calls the same `GET /products/{barcode}` endpoint as SHOP-10.
- [ ] The returned product is used to add a pantry item.

**Offline:** §6.5 Pantry: readable offline, and every write works offline and is queued until sync. The lookup needs a network. Saving the item after that is a pantry write and works offline.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `SHOP-10-api`, `PANT-02-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

**Open question:** §4 does not say what the screen does on a 404.

## PANT-09-ios — Barcode scan for a pantry item

**User value:** A person can scan a barcode and file that product in the pantry.

**Acceptance criteria:**
- [ ] The camera scans a barcode.
- [ ] The client calls the same `GET /products/{barcode}` endpoint as SHOP-10.
- [ ] The returned product is used to add a pantry item.

**Offline:** §6.5 Pantry: readable offline, and every write works offline and is queued until sync. The lookup needs a network. Saving the item after that is a pantry write and works offline.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `SHOP-10-api`, `PANT-02-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

**Open question:** §4 does not say what the screen does on a 404.

## PANT-09-android — Barcode scan for a pantry item

**User value:** A person can scan a barcode and file that product in the pantry.

**Acceptance criteria:**
- [ ] The camera scans a barcode.
- [ ] The client calls the same `GET /products/{barcode}` endpoint as SHOP-10.
- [ ] The returned product is used to add a pantry item.

**Offline:** §6.5 Pantry: readable offline, and every write works offline and is queued until sync. The lookup needs a network. Saving the item after that is a pantry write and works offline.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `SHOP-10-api`, `PANT-02-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

**Open question:** §4 does not say what the screen does on a 404.

## PANT-10-web — Photo of a pantry item

**User value:** A person can attach a photo of what the packet looks like.

**Acceptance criteria:**
- [ ] The user can add a photo to a pantry item.
- [ ] The photo is an attachment (CORE-11).

**Offline:** §6.5 Pantry says every write works offline. §6.5 also says maintenance attachment bytes queue until online. See CORE-11.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `PANT-02-web`, `CORE-11-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

## PANT-10-ios — Photo of a pantry item

**User value:** A person can attach a photo of what the packet looks like.

**Acceptance criteria:**
- [ ] The user can add a photo to a pantry item.
- [ ] The photo is an attachment (CORE-11).

**Offline:** §6.5 Pantry says every write works offline. §6.5 also says maintenance attachment bytes queue until online. See CORE-11.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `PANT-02-ios`, `CORE-11-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

## PANT-10-android — Photo of a pantry item

**User value:** A person can attach a photo of what the packet looks like.

**Acceptance criteria:**
- [ ] The user can add a photo to a pantry item.
- [ ] The photo is an attachment (CORE-11).

**Offline:** §6.5 Pantry says every write works offline. §6.5 also says maintenance attachment bytes queue until online. See CORE-11.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `PANT-02-android`, `CORE-11-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

## PANT-11-api — Consumption stats

**User value:** The household can see how fast each item is used up, from the movements it already recorded.

**Acceptance criteria:**
- [ ] `GET /pantry/usage` returns, per item, the average consumption per week computed from movements.

**Offline:** Computing the report needs a network. The movements it reads were allowed to be written offline (§6.5).

**Module off:** Pantry off: the report is rejected. Shopping and maintenance flags do not gate it.

**Dependencies:** `PANT-03-api`

**Skill:** `.claude/skills/node-backend/SKILL.md`

## PANT-11-web — Usage stats

**User value:** A person can see the average weekly use of each pantry item.

**Acceptance criteria:**
- [ ] The screen shows average consumption per week per item.
- [ ] The figure comes from movements, via PANT-11-api.

**Offline:** §6.5 Pantry reads work offline. This screen's source in §7 is `GET /pantry/usage`, which needs a network.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `PANT-11-api`, `PANT-07-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

**Open question:** §6.5 says pantry is readable offline and §7 computes this report on the server. Does the screen show a cached report offline, or only while online?

## PANT-11-ios — Usage stats

**User value:** A person can see the average weekly use of each pantry item.

**Acceptance criteria:**
- [ ] The screen shows average consumption per week per item.
- [ ] The figure comes from movements, via PANT-11-api.

**Offline:** §6.5 Pantry reads work offline. This screen's source in §7 is `GET /pantry/usage`, which needs a network.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `PANT-11-api`, `PANT-07-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

**Open question:** §6.5 says pantry is readable offline and §7 computes this report on the server. Does the screen show a cached report offline, or only while online?

## PANT-11-android — Usage stats

**User value:** A person can see the average weekly use of each pantry item.

**Acceptance criteria:**
- [ ] The screen shows average consumption per week per item.
- [ ] The figure comes from movements, via PANT-11-api.

**Offline:** §6.5 Pantry reads work offline. This screen's source in §7 is `GET /pantry/usage`, which needs a network.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `PANT-11-api`, `PANT-07-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

**Open question:** §6.5 says pantry is readable offline and §7 computes this report on the server. Does the screen show a cached report offline, or only while online?

## PANT-12-api — Recipes fully in stock

**User value:** The household can ask what it can cook from what is actually in the kitchen.

**Acceptance criteria:**
- [ ] `GET /pantry/can-make` returns recipes whose ingredients are all in stock.

**Offline:** The query needs a network. The pantry quantities it reads may have been written offline (§6.5).

**Module off:** Pantry off: the query is rejected.

**Dependencies:** `PANT-03-api`

**Skill:** `.claude/skills/node-backend/SKILL.md`

**Open question:** §4 does not describe creating or editing recipes, and recipes are in the domain model. Is recipe authoring part of this story, or do recipe rows arrive some other way?

## PANT-12-web — What can I make

**User value:** A person can see the recipes they can make with what is in stock.

**Acceptance criteria:**
- [ ] The screen lists recipes whose ingredients are all in stock.
- [ ] The list comes from PANT-12-api.

**Offline:** §6.5 Pantry reads work offline. §7's can-make query needs a network.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `PANT-12-api`, `PANT-02-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

**Open question:** §4 does not say the user can create or edit recipes. Without that, the screen has nothing to list unless recipes arrive another way. Same offline-versus-server-report question as PANT-11.

## PANT-12-ios — What can I make

**User value:** A person can see the recipes they can make with what is in stock.

**Acceptance criteria:**
- [ ] The screen lists recipes whose ingredients are all in stock.
- [ ] The list comes from PANT-12-api.

**Offline:** §6.5 Pantry reads work offline. §7's can-make query needs a network.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `PANT-12-api`, `PANT-02-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

**Open question:** §4 does not say the user can create or edit recipes. Without that, the screen has nothing to list unless recipes arrive another way. Same offline-versus-server-report question as PANT-11.

## PANT-12-android — What can I make

**User value:** A person can see the recipes they can make with what is in stock.

**Acceptance criteria:**
- [ ] The screen lists recipes whose ingredients are all in stock.
- [ ] The list comes from PANT-12-api.

**Offline:** §6.5 Pantry reads work offline. §7's can-make query needs a network.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `PANT-12-api`, `PANT-02-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

**Open question:** §4 does not say the user can create or edit recipes. Without that, the screen has nothing to list unless recipes arrive another way. Same offline-versus-server-report question as PANT-11.

## PANT-13-web — Unit conversion

**User value:** The household can keep a conversion table and see amounts in the unit it prefers.

**Acceptance criteria:**
- [ ] The household has a unit conversion table.
- [ ] Amounts display in the preferred unit.

**Offline:** §6.5 Pantry: readable offline, and every write works offline and is queued until sync.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `PANT-02-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

**Open question:** §4 does not say where the preferred unit is stored. It is not a field on `profile` or `household` in §8.

## PANT-13-ios — Unit conversion

**User value:** The household can keep a conversion table and see amounts in the unit it prefers.

**Acceptance criteria:**
- [ ] The household has a unit conversion table.
- [ ] Amounts display in the preferred unit.

**Offline:** §6.5 Pantry: readable offline, and every write works offline and is queued until sync.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `PANT-02-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

**Open question:** §4 does not say where the preferred unit is stored. It is not a field on `profile` or `household` in §8.

## PANT-13-android — Unit conversion

**User value:** The household can keep a conversion table and see amounts in the unit it prefers.

**Acceptance criteria:**
- [ ] The household has a unit conversion table.
- [ ] Amounts display in the preferred unit.

**Offline:** §6.5 Pantry: readable offline, and every write works offline and is queued until sync.

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error.

**Dependencies:** `PANT-02-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

**Open question:** §4 does not say where the preferred unit is stored. It is not a field on `profile` or `household` in §8.

## PANT-14 — Low stock adds a shopping line

**User value:** When something falls to its low line, it shows up on the shopping list on its own, once.

**Acceptance criteria:**
- [ ] When quantity crosses from above the threshold to at or below it, one `list_item` is inserted (X1).
- [ ] The line has source pantry and `pantry_item_id` set.
- [ ] It is inserted into the household's oldest list that is not archived and not a template.
- [ ] A second open pantry line for the same item on that list is not inserted.
- [ ] If no such list exists, nothing is inserted.
- [ ] The insert runs only when shopping is on, auto-add is on, and the threshold is set.

**Offline:** The trigger runs when the quantity change is applied on the server, including a delta that was queued offline (§6.5 Pantry).

**Module off:** Pantry off (X7): the quantity change is rejected, so the trigger does not run. Shopping off: X1 does not insert a list line.

**Dependencies:** `PANT-03-api`, `CORE-02-api`

**Skill:** `.claude/skills/sql-supabase/SKILL.md`

**Open question:** The pantry-only row of the §9 matrix says low-stock items show a "low" badge and go nowhere. That badge has no feature ID. §10 also writes a low-stock notification when X1 fires, and that notice is not in the §4 acceptance summary. Are the badge and the notice part of this story?

## PANT-15-web — Restock from a checked-off list item

**User value:** Accepting restock puts the bought amount back into the pantry as a restock movement.

**Acceptance criteria:**
- [ ] Accepting restock creates a `pantry_movement` with reason `restocked`.
- [ ] The delta is the list item's quantity, or 1 when the quantity is empty (X2).

**Offline:** §6.5 Pantry: readable offline, and every write works offline and is queued until sync.

**Module off:** X2 runs only when pantry is on and the list item source is pantry. Pantry off: the movement is rejected (X7). Shopping only (§9 matrix): there is no restock offer (SHOP-16).

**Dependencies:** `SHOP-16-web`, `PANT-03-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

## PANT-15-ios — Restock from a checked-off list item

**User value:** Accepting restock puts the bought amount back into the pantry as a restock movement.

**Acceptance criteria:**
- [ ] Accepting restock creates a `pantry_movement` with reason `restocked`.
- [ ] The delta is the list item's quantity, or 1 when the quantity is empty (X2).

**Offline:** §6.5 Pantry: readable offline, and every write works offline and is queued until sync.

**Module off:** X2 runs only when pantry is on and the list item source is pantry. Pantry off: the movement is rejected (X7). Shopping only (§9 matrix): there is no restock offer (SHOP-16).

**Dependencies:** `SHOP-16-ios`, `PANT-03-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

## PANT-15-android — Restock from a checked-off list item

**User value:** Accepting restock puts the bought amount back into the pantry as a restock movement.

**Acceptance criteria:**
- [ ] Accepting restock creates a `pantry_movement` with reason `restocked`.
- [ ] The delta is the list item's quantity, or 1 when the quantity is empty (X2).

**Offline:** §6.5 Pantry: readable offline, and every write works offline and is queued until sync.

**Module off:** X2 runs only when pantry is on and the list item source is pantry. Pantry off: the movement is rejected (X7). Shopping only (§9 matrix): there is no restock offer (SHOP-16).

**Dependencies:** `SHOP-16-android`, `PANT-03-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

## PANT-16-api — Daily eat-soon push

**User value:** Once a day, people who want the reminder hear about food that expires in the next three days.

**Acceptance criteria:**
- [ ] A daily job enqueues an eat-soon notice for an item expiring within 3 days (§10).
- [ ] The job is deduplicated per item per window and respects `notification_pref`.
- [ ] The household's pantry module must be on.
- [ ] `POST /internal/cron/deliver-notifications` sends unsent notices to the user's device tokens and sets `sent_at`.

**Offline:** Delivery needs a network. The expiry dates it reads may have been edited offline (§6.5).

**Module off:** Pantry off: the eat-soon insert does not run (§10 joins on `module_pantry`). A user with the preference off is skipped.

**Dependencies:** `PANT-03-api`, `CORE-12-web`, `CORE-12-ios`, `CORE-12-android`

**Skill:** `.claude/skills/node-backend/SKILL.md`; `.claude/skills/sql-supabase/SKILL.md`

**Open question:** Device-token registration is `POST /devices` and has no feature ID. §10 also says clients sync a `notification` inbox, and `notification` is not in the §8 `SyncTable` list. Does this story include token registration and the inbox, or only the daily eat-soon send?

## PANT-16-web — Eat-soon push notifications

**User value:** The person gets a daily nudge about food that is about to expire.

**Acceptance criteria:**
- [ ] The device receives the daily eat-soon push from PANT-16-api.
- [ ] The notice respects the per-household preference from CORE-12.

**Offline:** Receiving a push needs a network. The pantry list behind it is readable offline (§6.5).

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error. No eat-soon push is sent while pantry is off.

**Dependencies:** `PANT-16-api`, `CORE-12-web`, `PANT-05-web`

**Skill:** `.claude/skills/react-web/SKILL.md`

**Open question:** Same inbox and device-token question as PANT-16-api. §4 does not describe an in-app inbox screen.

## PANT-16-ios — Eat-soon push notifications

**User value:** The person gets a daily nudge about food that is about to expire.

**Acceptance criteria:**
- [ ] The device receives the daily eat-soon push from PANT-16-api.
- [ ] The notice respects the per-household preference from CORE-12.

**Offline:** Receiving a push needs a network. The pantry list behind it is readable offline (§6.5).

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error. No eat-soon push is sent while pantry is off.

**Dependencies:** `PANT-16-api`, `CORE-12-ios`, `PANT-05-ios`

**Skill:** `.claude/skills/swift-ios/SKILL.md`

**Open question:** Same inbox and device-token question as PANT-16-api. §4 does not describe an in-app inbox screen.

## PANT-16-android — Eat-soon push notifications

**User value:** The person gets a daily nudge about food that is about to expire.

**Acceptance criteria:**
- [ ] The device receives the daily eat-soon push from PANT-16-api.
- [ ] The notice respects the per-household preference from CORE-12.

**Offline:** Receiving a push needs a network. The pantry list behind it is readable offline (§6.5).

**Module off:** Pantry off (X7): the pantry tab is hidden and a pantry-table mutation is rejected with `module_disabled`. This feature stays hidden and does not error. No eat-soon push is sent while pantry is off.

**Dependencies:** `PANT-16-api`, `CORE-12-android`, `PANT-05-android`

**Skill:** `.claude/skills/kotlin-android/SKILL.md`

**Open question:** Same inbox and device-token question as PANT-16-api. §4 does not describe an in-app inbox screen.
