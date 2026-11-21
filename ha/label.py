"""Give entities the tv_widget label (creating the label if it's missing); a leading "-" takes it off instead.

Usage: python ha/label.py <config.json> sensor.x weather.y -cover.z ...
"""

import sys

from hass_ws import Hass

LABEL = "tv_widget"

ha = Hass(sys.argv[1], timeout=10)
labels = ha.call(type="config/label_registry/list")
label = next((lb for lb in labels if lb["label_id"] == LABEL), None)
if not label:
    label = ha.call(
        type="config/label_registry/create",
        name=LABEL,
        icon="mdi:television",
        description="Shown in the widget row on the TV's home screen",
    )
    print("created label", label["label_id"])
for arg in sys.argv[2:]:
    drop = arg.startswith("-")
    entity = arg.lstrip("-")
    have = ha.call(type="config/entity_registry/get", entity_id=entity).get("labels") or []
    if drop:
        if label["label_id"] not in have:
            print("not labelled", entity)
            continue
        ha.call(
            type="config/entity_registry/update",
            entity_id=entity,
            labels=[lb for lb in have if lb != label["label_id"]],
        )
        print("unlabelled", entity)
    elif label["label_id"] in have:
        print("already", entity)
    else:
        ha.call(type="config/entity_registry/update", entity_id=entity, labels=[*have, label["label_id"]])
        print("labelled", entity)
ha.close()
