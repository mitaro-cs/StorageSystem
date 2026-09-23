<script lang="ts">
	import { encode } from 'uqr';

	// QR-код векторно: чёрные модули на белом поле с отступом — сканируется в любой теме.
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

<svg class="qr" viewBox="-2 -2 {qr.size + 4} {qr.size + 4}" role="img" aria-label={label}>
	<rect x="-2" y="-2" width={qr.size + 4} height={qr.size + 4} fill="#fff" />
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
