<script lang="ts">
	import { encode } from 'uqr';

	// QR-код векторно: чёрные модули на белом поле — сканируется в любой теме.
	let { value, label = 'QR-код' }: { value: string; label?: string } = $props();

	const qr = $derived.by(() => {
		const { data } = encode(value, { ecc: 'M' });
		let path = '';
		data.forEach((row, y) =>
			row.forEach((on, x) => {
				if (on) path += `M${x} ${y}h1v1h-1z`;
			})
		);
		return { size: data.length, path };
	});
</script>

<!-- Белое поле в 4 модуля, как требует стандарт QR: иначе на тёмной странице часть приложений-сканеров
     (Google Authenticator, Яндекс Ключ) код не находит. -->
<svg class="qr" viewBox="-4 -4 {qr.size + 8} {qr.size + 8}" role="img" aria-label={label}>
	<rect x="-4" y="-4" width={qr.size + 8} height={qr.size + 8} fill="#fff" />
	<path d={qr.path} fill="#0d0d0f" shape-rendering="crispEdges" />
</svg>

<style>
	.qr {
		display: block;
		width: 100%;
		height: auto;
		border-radius: 12px;
	}
</style>
