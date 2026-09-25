/**
 * Обход ошибки WebKit в iOS 26–27 (сайт, установленный на экран «Домой»): после того как закрылась
 * клавиатура, видимая часть страницы «съезжает» относительно той, к которой система прикалывает
 * position: fixed, — нижняя панель поднимается и ездит при прокрутке (WebKit bug 297779,
 * FB19889436). Считаем, насколько элемент у нижнего края оказался выше видимого края, и сдвигаем
 * панель обратно (переменная --vv-shift); пока открыта клавиатура — прячем панель (класс kb-open).
 */

export interface ViewportState {
	/** visualViewport.offsetTop — где видимая часть относительно раскладки. */
	offsetTop: number;
	/** visualViewport.scale — при увеличении пальцами ничего не трогаем. */
	scale: number;
	/** Где раскладка ставит элемент с bottom: 0 (getBoundingClientRect().bottom). */
	fixedBottom: number;
	/** Высота экрана приложения, замеренная, пока всё было в порядке. */
	screenHeight: number;
}

/** На сколько опустить прижатое к низу, чтобы оно снова стояло у видимого края (0 — всё в порядке). */
export function viewportShift(v: ViewportState): number {
	if (Math.abs(v.scale - 1) > 0.01 || v.screenHeight <= 0) return 0;
	const shift = v.screenHeight + v.offsetTop - v.fixedBottom;
	// Меньше пикселя — округление; больше 300 — что-то другое (клавиатура, поворот), не лечим.
	return Math.abs(shift) < 1 || Math.abs(shift) > 300 ? 0 : Math.round(shift);
}

/** Поле, в котором сейчас печатают: над ним открыта клавиатура. */
export function typingIn(el: Element | null): boolean {
	if (!el) return false;
	if ((el as HTMLElement).isContentEditable) return true;
	if (el.tagName === 'TEXTAREA') return true;
	if (el.tagName !== 'INPUT') return false;
	const type = (el as HTMLInputElement).type;
	return !/^(button|checkbox|radio|range|color|file|submit|reset|image|hidden)$/.test(type);
}

function iosHomeScreenApp(): boolean {
	const ua = navigator.userAgent;
	const ios = /iPhone|iPad|iPod/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1);
	const standalone =
		matchMedia('(display-mode: standalone)').matches ||
		(navigator as Navigator & { standalone?: boolean }).standalone === true;
	return ios && standalone;
}

/** Следит за видимой областью; только в установленном приложении на iPhone и iPad. */
export function watchViewport(): () => void {
	const vv = window.visualViewport;
	if (!vv || !iosHomeScreenApp()) return () => {};
	const root = document.documentElement;
	// Невидимая метка у нижнего края: по ней видно, куда раскладка ставит панель.
	const probe = document.createElement('div');
	probe.setAttribute('aria-hidden', 'true');
	probe.style.cssText =
		'position:fixed;left:0;bottom:0;width:0;height:0;visibility:hidden;pointer-events:none';
	document.body.append(probe);

	let screenHeight = 0;
	let width = innerWidth;
	let shift = 0;
	let typing = false;
	let queued = false;

	function update() {
		queued = false;
		const nowTyping = typingIn(document.activeElement);
		if (nowTyping !== typing) {
			typing = nowTyping;
			root.classList.toggle('kb-open', typing);
		}
		// Повернули экран — высота другая, замеряем заново.
		if (innerWidth !== width) {
			width = innerWidth;
			screenHeight = 0;
		}
		const clean = !typing && Math.abs(vv!.scale - 1) < 0.01 && vv!.offsetTop === 0;
		if (clean) screenHeight = Math.max(screenHeight, Math.round(vv!.height));
		const next = typing
			? 0
			: viewportShift({
					offsetTop: vv!.offsetTop,
					scale: vv!.scale,
					fixedBottom: probe.getBoundingClientRect().bottom,
					screenHeight
				});
		if (next !== shift) {
			shift = next;
			if (shift) root.style.setProperty('--vv-shift', `${shift}px`);
			else root.style.removeProperty('--vv-shift');
		}
	}
	function schedule() {
		if (queued) return;
		queued = true;
		requestAnimationFrame(update);
	}
	// После того как поле потеряло фокус, iOS ещё какое-то время убирает клавиатуру.
	function blurred() {
		schedule();
		setTimeout(schedule, 350);
	}

	vv.addEventListener('resize', schedule);
	vv.addEventListener('scroll', schedule);
	addEventListener('scroll', schedule, { passive: true });
	addEventListener('focusin', schedule);
	addEventListener('focusout', blurred);
	schedule();
	return () => {
		vv.removeEventListener('resize', schedule);
		vv.removeEventListener('scroll', schedule);
		removeEventListener('scroll', schedule);
		removeEventListener('focusin', schedule);
		removeEventListener('focusout', blurred);
		probe.remove();
		root.classList.remove('kb-open');
		root.style.removeProperty('--vv-shift');
	};
}
