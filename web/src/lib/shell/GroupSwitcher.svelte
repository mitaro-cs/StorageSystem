<script lang="ts">
	import { t } from '$lib/i18n/ru';
	import { groups, isMulti, selectGroup, session } from '$lib/session.svelte';
	import Avatar from '$lib/ui/Avatar.svelte';
	import { appIcon, iconSrc } from '$lib/appIcon.svelte';

	let { compact = false }: { compact?: boolean } = $props();
</script>

{#if isMulti()}
	<div class="switcher" class:compact role="radiogroup" aria-label="Группа">
		<button
			class="g all"
			role="radio"
			aria-checked={session.groupId === null}
			title={t.nav.allGroups}
			onclick={() => selectGroup(null)}
		>
			<!-- «Все группы» — логотип сайта -->
			<span class="ring"><img src={iconSrc(appIcon.id)} alt="" width="32" height="32" /></span>
		</button>
		{#each groups() as g (g.id)}
			<button
				class="g"
				role="radio"
				aria-checked={session.groupId === g.id}
				title={g.name}
				onclick={() => selectGroup(g.id)}
			>
				<span class="ring"
					><Avatar id={g.id} name={g.name} avatar={g.avatar} size={32} kind="group" square /></span
				>
			</button>
		{/each}
	</div>
{/if}

<style>
	.switcher {
		display: flex;
		gap: 6px;
		flex-wrap: wrap;
	}
	.g {
		padding: 0;
		border: 0;
		background: transparent;
		border-radius: var(--r);
	}
	.ring {
		display: grid;
		place-items: center;
		width: 40px;
		height: 40px;
		border-radius: var(--r);
		border: 2px solid transparent;
		transition:
			border-color var(--dur) var(--ease),
			transform 120ms var(--ease);
	}
	.g:hover .ring {
		transform: translateY(-1px);
	}
	.g[aria-checked='true'] .ring {
		border-color: var(--accent);
	}
	.all .ring img {
		border-radius: 9px;
	}
	.all .ring {
		width: 40px;
	}
	.compact .ring {
		width: 36px;
		height: 36px;
	}
</style>
