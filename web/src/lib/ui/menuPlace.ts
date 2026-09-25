const GAP = 4;
const MARGIN = 8;

/**
 * Где показать выпадающее меню: под кнопкой, прижав к её правому краю; если снизу не помещается —
 * над ней. В любом случае целиком на экране, с отступом 8 px от краёв.
 */
export function placeMenu(
	trigger: { top: number; bottom: number; right: number },
	size: { width: number; height: number },
	viewport: { width: number; height: number }
): { top: number; left: number; up: boolean } {
	const below = trigger.bottom + GAP;
	const above = trigger.top - GAP - size.height;
	const fitsBelow = below + size.height <= viewport.height - MARGIN;
	const up = !fitsBelow && above >= MARGIN;
	const top = Math.max(
		MARGIN,
		Math.min(up ? above : below, viewport.height - MARGIN - size.height)
	);
	const left = Math.max(
		MARGIN,
		Math.min(trigger.right - size.width, viewport.width - MARGIN - size.width)
	);
	return { top, left, up };
}
