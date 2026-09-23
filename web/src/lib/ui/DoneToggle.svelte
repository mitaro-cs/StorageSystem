<script lang="ts">
	let { done, onchange, label }: { done: boolean; onchange: (v: boolean) => void; label: string } =
		$props();
</script>

<button
	class="done"
	class:on={done}
	role="checkbox"
	aria-checked={done}
	aria-label={label}
	title={done ? 'Отметить как невыполненное' : 'Отметить как выполненное'}
	onclick={(e) => {
		e.preventDefault();
		e.stopPropagation();
		onchange(!done);
	}}
>
	<svg viewBox="0 0 24 24" aria-hidden="true">
		<circle cx="12" cy="12" r="10" class="ring" />
		<path d="M7.5 12.5l3 3 6-6.5" class="tick" />
	</svg>
</button>

<style>
	.done {
		flex: none;
		display: grid;
		place-items: center;
		width: 32px;
		height: 32px;
		margin: -4px;
		padding: 4px;
		border: 0;
		border-radius: 50%;
		background: transparent;
		-webkit-tap-highlight-color: transparent;
	}
	svg {
		width: 24px;
		height: 24px;
		overflow: visible;
	}
	.ring {
		fill: transparent;
		stroke: var(--border-strong);
		stroke-width: 1.8;
		transition:
			fill 220ms var(--ease),
			stroke 220ms var(--ease);
	}
	.tick {
		fill: none;
		stroke: var(--accent-text);
		stroke-width: 2.4;
		stroke-linecap: round;
		stroke-linejoin: round;
		stroke-dasharray: 16;
		stroke-dashoffset: 16;
		transition: stroke-dashoffset 260ms var(--ease) 60ms;
	}
	.done:hover .ring {
		stroke: var(--accent);
	}
	.on .ring {
		fill: var(--accent);
		stroke: var(--accent);
	}
	.on .tick {
		stroke-dashoffset: 0;
	}
	.on svg {
		animation: pulse 320ms var(--ease);
	}
	@keyframes pulse {
		50% {
			transform: scale(1.15);
		}
	}
</style>
