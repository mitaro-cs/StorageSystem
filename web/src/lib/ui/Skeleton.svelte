<script lang="ts">
	let { lines = 3, card = true }: { lines?: number; card?: boolean } = $props();
</script>

<div class:card class="skeleton" aria-hidden="true">
	{#each Array.from({ length: lines }, (_, i) => i) as i (i)}
		<div class="bar" style:width="{i === 0 ? 45 : 100 - ((i * 17) % 35)}%"></div>
	{/each}
</div>

<style>
	.skeleton {
		display: flex;
		flex-direction: column;
		gap: 10px;
	}
	.bar {
		height: 12px;
		border-radius: 6px;
		background: linear-gradient(90deg, var(--surface-2), var(--surface-3), var(--surface-2));
		background-size: 200% 100%;
		animation: shimmer 1.4s ease-in-out infinite;
	}
	.bar:first-child {
		height: 16px;
	}
	@keyframes shimmer {
		from {
			background-position: 100% 0;
		}
		to {
			background-position: -100% 0;
		}
	}
</style>
