<script lang="ts">
	import { Bell } from '@lucide/svelte';
	import { bell } from '$lib/notify.svelte';

	const label = $derived(bell.unread ? `Уведомления: непрочитанных ${bell.unread}` : 'Уведомления');
</script>

<a class="circle bell" href="/notifications" aria-label={label} title={label}>
	<Bell size={19} />
	{#if bell.unread}<span class="badge num" aria-hidden="true"
			>{bell.unread > 99 ? '99+' : bell.unread}</span
		>{/if}
</a>

<style>
	.bell {
		position: relative;
	}
	.badge {
		position: absolute;
		top: -4px;
		right: -4px;
		min-width: 20px;
		height: 20px;
		padding: 0 5px;
		border-radius: 10px;
		background: var(--danger);
		color: #fff;
		font-size: 11.5px;
		font-weight: 700;
		line-height: 20px;
		text-align: center;
		box-shadow: 0 0 0 2px var(--bg);
		animation: pop 260ms var(--ease);
	}
	@keyframes pop {
		from {
			transform: scale(0.4);
		}
	}
</style>
