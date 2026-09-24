<script lang="ts">
	import { onDestroy, onMount } from 'svelte';
	import type {
		PDFDocumentLoadingTask,
		PDFDocumentProxy,
		RenderTask
	} from 'pdfjs-dist/legacy/build/pdf.mjs';

	// PDF рисуется прямо в приложении (pdf.js, свой код, без внешних запросов): одинаково на
	// компьютере, Android и iPhone, где встроенный просмотр в рамке показывает только первую страницу.
	let { src, zoom = 1 }: { src: string; zoom?: number } = $props();

	let host: HTMLDivElement | undefined = $state();
	let sizes = $state<{ w: number; h: number }[]>([]);
	let width = $state(0);
	let error = $state('');
	let doc: PDFDocumentProxy | null = null;
	let loading: PDFDocumentLoadingTask | null = null;
	let observer: IntersectionObserver | undefined;
	// Служебное, не для отрисовки разметки — обычные объекты, не реактивные.
	const tasks: Record<number, RenderTask> = {};
	let drawn: Record<number, number> = {};

	onMount(() => {
		let cancelled = false;
		(async () => {
			try {
				const pdfjs = await import('pdfjs-dist/legacy/build/pdf.mjs');
				const worker = (await import('pdfjs-dist/legacy/build/pdf.worker.min.mjs?url')).default;
				pdfjs.GlobalWorkerOptions.workerSrc = worker;
				// Файл берём сами: так его отдаст service worker из сохранённых, если нет сети.
				const res = await fetch(src, { credentials: 'same-origin' });
				if (!res.ok) throw new Error(`Ошибка ${res.status}`);
				const data = new Uint8Array(await res.arrayBuffer());
				loading = pdfjs.getDocument({ data });
				const d = await loading.promise;
				if (cancelled) return void loading.destroy();
				doc = d;
				const out: { w: number; h: number }[] = [];
				for (let i = 1; i <= d.numPages; i++) {
					const v = (await d.getPage(i)).getViewport({ scale: 1 });
					out.push({ w: v.width, h: v.height });
				}
				sizes = out;
			} catch (e) {
				error = e instanceof Error ? e.message : 'Не удалось открыть PDF';
			}
		})();
		const ro = new ResizeObserver(([e]) => (width = e.contentRect.width));
		if (host) ro.observe(host);
		return () => {
			cancelled = true;
			ro.disconnect();
		};
	});

	onDestroy(() => {
		observer?.disconnect();
		for (const t of Object.values(tasks)) t.cancel();
		loading?.destroy();
	});

	const pageWidth = $derived(Math.max(200, width * zoom));

	async function draw(canvas: HTMLCanvasElement, n: number) {
		if (!doc || drawn[n] === pageWidth) return;
		tasks[n]?.cancel();
		const page = await doc.getPage(n);
		const base = page.getViewport({ scale: 1 });
		const dpr = Math.min(window.devicePixelRatio || 1, 2.5);
		const viewport = page.getViewport({ scale: (pageWidth / base.width) * dpr });
		canvas.width = Math.floor(viewport.width);
		canvas.height = Math.floor(viewport.height);
		const task = page.render({ canvas, viewport });
		tasks[n] = task;
		try {
			await task.promise;
			drawn[n] = pageWidth;
		} catch {
			// отменили — нарисуем при следующем показе
		}
	}

	// Страницы рисуются, когда до них докрутили (и перерисовываются после смены масштаба).
	function lazy(canvas: HTMLCanvasElement) {
		observer ??= new IntersectionObserver(
			(entries) => {
				for (const e of entries)
					if (e.isIntersecting) {
						const c = e.target as HTMLCanvasElement;
						draw(c, Number(c.dataset.page));
					}
			},
			{ rootMargin: '600px 0px' }
		);
		observer.observe(canvas);
		return { destroy: () => observer?.unobserve(canvas) };
	}

	$effect(() => {
		void pageWidth;
		// Смена масштаба: видимые страницы перерисуются при следующем пересечении.
		drawn = {};
		if (!observer || !host) return;
		for (const c of host.querySelectorAll('canvas')) {
			observer.unobserve(c);
			observer.observe(c);
		}
	});
</script>

<div class="pdf" bind:this={host}>
	{#if error}
		<p class="error-text">{error}</p>
	{:else if sizes.length === 0}
		<div class="loading" aria-busy="true">
			<span class="sheet"></span>
			<span class="faint small">Открываем PDF…</span>
		</div>
	{:else}
		{#each sizes as s, i (i)}
			<canvas
				data-page={i + 1}
				use:lazy
				style:width="{pageWidth}px"
				style:aspect-ratio="{s.w} / {s.h}"
				aria-label="Страница {i + 1} из {sizes.length}"
			></canvas>
		{/each}
	{/if}
</div>

<style>
	.pdf {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 12px;
		width: 100%;
	}
	canvas {
		display: block;
		max-width: none;
		height: auto;
		background: white;
		border-radius: 4px;
		box-shadow: 0 2px 10px rgb(0 0 0 / 0.18);
	}
	.loading {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 14px;
		padding: 48px 0;
	}
	.sheet {
		width: 150px;
		height: 200px;
		border-radius: 6px;
		background: linear-gradient(
			100deg,
			var(--surface-2) 30%,
			color-mix(in srgb, var(--surface-2) 40%, white) 50%,
			var(--surface-2) 70%
		);
		background-size: 300% 100%;
		animation: shimmer 1.2s linear infinite;
	}
	@keyframes shimmer {
		from {
			background-position: 100% 0;
		}
		to {
			background-position: 0 0;
		}
	}
</style>
