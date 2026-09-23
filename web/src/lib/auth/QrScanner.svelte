<script lang="ts">
	import { goto } from '$app/navigation';
	import { onDestroy } from 'svelte';
	import Modal from '$lib/ui/Modal.svelte';

	// Сканер QR внутри приложения: нужен установленному приложению на iPhone — у него своя сессия,
	// отдельная от Safari, поэтому камера телефона тут не поможет.
	let { open = $bindable(false) }: { open?: boolean } = $props();

	let video: HTMLVideoElement | undefined = $state();
	let error = $state('');
	let stream: MediaStream | null = null;
	let frame = 0;

	type Detect = (v: HTMLVideoElement) => Promise<string | null>;

	async function detector(): Promise<Detect> {
		const BD = (
			window as unknown as {
				BarcodeDetector?: new (o: { formats: string[] }) => {
					detect: (v: HTMLVideoElement) => Promise<{ rawValue: string }[]>;
				};
			}
		).BarcodeDetector;
		if (BD) {
			const d = new BD({ formats: ['qr_code'] });
			return async (v) => (await d.detect(v))[0]?.rawValue ?? null;
		}
		// Safari не умеет BarcodeDetector — распознаём сами (библиотека грузится только здесь).
		const jsQR = (await import('jsqr')).default;
		const canvas = document.createElement('canvas');
		const ctx = canvas.getContext('2d', { willReadFrequently: true })!;
		return async (v) => {
			canvas.width = v.videoWidth;
			canvas.height = v.videoHeight;
			ctx.drawImage(v, 0, 0);
			const img = ctx.getImageData(0, 0, canvas.width, canvas.height);
			return jsQR(img.data, img.width, img.height)?.data ?? null;
		};
	}

	function stop() {
		cancelAnimationFrame(frame);
		stream?.getTracks().forEach((t) => t.stop());
		stream = null;
	}

	async function startCamera() {
		error = '';
		try {
			stream = await navigator.mediaDevices.getUserMedia({
				video: { facingMode: 'environment' },
				audio: false
			});
		} catch {
			error = 'Нет доступа к камере. Разрешите его в настройках браузера для этого сайта.';
			return;
		}
		if (!video) return;
		video.srcObject = stream;
		await video.play().catch(() => {});
		const detect = await detector();
		const tick = async () => {
			if (!stream || !video) return;
			if (video.readyState >= 2) {
				const text = await detect(video).catch(() => null);
				const path = text && linkPath(text);
				if (path) {
					stop();
					open = false;
					return goto(path);
				}
				if (text) error = 'Это не код входа groupbase';
			}
			frame = requestAnimationFrame(tick);
		};
		tick();
	}

	/** Путь /link/… только с нашего же сайта. */
	function linkPath(text: string): string | null {
		try {
			const u = new URL(text);
			return u.origin === location.origin && /^\/link\/[\w-]+$/.test(u.pathname)
				? u.pathname
				: null;
		} catch {
			return null;
		}
	}

	$effect(() => {
		if (open && video) startCamera();
		if (!open) stop();
	});
	onDestroy(stop);
</script>

<Modal bind:open title="Сканировать QR-код">
	<div class="scan">
		<video bind:this={video} playsinline muted></video>
		<span class="frame" aria-hidden="true"></span>
	</div>
	<p class="muted small">Наведите камеру на QR-код на экране компьютера или другого телефона.</p>
	{#if error}<p class="error-text" role="alert">{error}</p>{/if}
</Modal>

<style>
	.scan {
		position: relative;
		aspect-ratio: 1;
		border-radius: var(--r-l);
		overflow: hidden;
		background: #000;
	}
	video {
		width: 100%;
		height: 100%;
		object-fit: cover;
	}
	.frame {
		position: absolute;
		inset: 18%;
		border: 3px solid rgb(255 255 255 / 0.85);
		border-radius: 24px;
		box-shadow: 0 0 0 999px rgb(0 0 0 / 0.35);
	}
</style>
