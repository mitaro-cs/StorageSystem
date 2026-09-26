<script lang="ts">
	let { done, onchange, label }: { done: boolean; onchange: (v: boolean) => void; label: string } =
		$props();
	// Отметили «сделано» — от кружка разлетаются искорки (только по нажатию, не при загрузке).
	let burst = $state(0);
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
		if (!done) burst++;
		onchange(!done);
	}}
>
	<svg viewBox="0 0 24 24" aria-hidden="true">
		<circle cx="12" cy="12" r="10" class="ring" />
		<path d="M7.5 12.5l3 3 6-6.5" class="tick" />
	</svg>
	{#key burst}
		{#if burst}
			<span class="burst" aria-hidden="true">
				{#each [0, 45, 90, 135, 180, 225, 270, 315] as a (a)}<i style:--a="{a}deg"></i>{/each}
			</span>
		{/if}
	{/key}
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
	.done {
		position: relative;
	}
	.on svg {
		animation: pulse 420ms cubic-bezier(0.3, 1.6, 0.5, 1);
	}
	@keyframes pulse {
		40% {
			transform: scale(1.25) rotate(-6deg);
		}
	}
	.burst {
		position: absolute;
		inset: 0;
		pointer-events: none;
	}
	.burst i {
		position: absolute;
		left: 50%;
		top: 50%;
		width: 5px;
		height: 5px;
		margin: -2.5px;
		border-radius: 50%;
		background: var(--accent);
		transform: rotate(var(--a)) translateY(-6px);
		animation: burst 560ms cubic-bezier(0.15, 0.8, 0.3, 1) forwards;
	}
	.burst i:nth-child(3n + 1) {
		background: var(--amber);
	}
	.burst i:nth-child(3n + 2) {
		background: var(--ok);
	}
	@keyframes burst {
		to {
			transform: rotate(var(--a)) translateY(-22px) scale(0.2);
			opacity: 0;
		}
	}
	@media (prefers-reduced-motion: reduce) {
		.on svg,
		.burst {
			animation: none;
			display: none;
		}
	}
</style>
