import { create } from 'zustand'
import { immer } from 'zustand/middleware/immer'
import toast from 'react-hot-toast'
import { apiService } from '@/services/api'
import type { WebSocketMessage, Order } from '@/types'

interface WebSocketState {
  isConnected: boolean
  isConnecting: boolean
  connectionAttempts: number
  lastMessage: WebSocketMessage | null
  orders: Map<number, Order>
  notifications: WebSocketMessage[]
}

interface WebSocketActions {
  connect: () => void
  disconnect: () => void
  sendMessage: (message: any) => void
  handleMessage: (message: WebSocketMessage) => void
  clearNotifications: () => void
  markNotificationAsRead: (id: string) => void
}

type WebSocketStore = WebSocketState & WebSocketActions

const MAX_RECONNECT_ATTEMPTS = 5
const RECONNECT_DELAY = 3000

export const useWebSocketStore = create<WebSocketStore>()(
  immer((set, get) => {
    let ws: WebSocket | null = null
    let reconnectTimer: NodeJS.Timeout | null = null

    return {
      // Initial State
      isConnected: false,
      isConnecting: false,
      connectionAttempts: 0,
      lastMessage: null,
      orders: new Map(),
      notifications: [],

      // Actions
      connect: () => {
        const state = get()
        
        if (state.isConnected || state.isConnecting) {
          return
        }

        set((draft) => {
          draft.isConnecting = true
        })

        try {
          ws = apiService.ws.connect(
            (message: WebSocketMessage) => {
              get().handleMessage(message)
            },
            (error: Event) => {
              console.error('WebSocket error:', error)
              
              set((draft) => {
                draft.isConnected = false
                draft.isConnecting = false
              })

              // Attempt to reconnect
              const attempts = get().connectionAttempts
              if (attempts < MAX_RECONNECT_ATTEMPTS) {
                set((draft) => {
                  draft.connectionAttempts = attempts + 1
                })

                reconnectTimer = setTimeout(() => {
                  console.log(`Reconnecting WebSocket (attempt ${attempts + 1})...`)
                  get().connect()
                }, RECONNECT_DELAY * Math.pow(2, attempts)) // Exponential backoff
              } else {
                toast.error('Connection lost. Please refresh the page.')
              }
            }
          )

          if (ws) {
            ws.onopen = () => {
              set((draft) => {
                draft.isConnected = true
                draft.isConnecting = false
                draft.connectionAttempts = 0
              })

              console.log('WebSocket connected successfully')
            }

            ws.onclose = (event) => {
              set((draft) => {
                draft.isConnected = false
                draft.isConnecting = false
              })

              console.log('WebSocket disconnected:', event.code, event.reason)

              // Don't reconnect if manually closed
              if (event.code !== 1000) {
                const attempts = get().connectionAttempts
                if (attempts < MAX_RECONNECT_ATTEMPTS) {
                  set((draft) => {
                    draft.connectionAttempts = attempts + 1
                  })

                  reconnectTimer = setTimeout(() => {
                    console.log(`Reconnecting WebSocket (attempt ${attempts + 1})...`)
                    get().connect()
                  }, RECONNECT_DELAY * Math.pow(2, attempts))
                }
              }
            }
          }
        } catch (error) {
          console.error('Failed to connect WebSocket:', error)
          
          set((draft) => {
            draft.isConnected = false
            draft.isConnecting = false
          })
        }
      },

      disconnect: () => {
        if (reconnectTimer) {
          clearTimeout(reconnectTimer)
          reconnectTimer = null
        }

        if (ws) {
          ws.close(1000, 'Manual disconnect')
          ws = null
        }

        set((draft) => {
          draft.isConnected = false
          draft.isConnecting = false
          draft.connectionAttempts = 0
        })
      },

      sendMessage: (message: any) => {
        if (ws && ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify(message))
        } else {
          console.warn('WebSocket not connected, cannot send message:', message)
        }
      },

      handleMessage: (message: WebSocketMessage) => {
        set((draft) => {
          draft.lastMessage = message
        })

        switch (message.type) {
          case 'ORDER_UPDATE':
            set((draft) => {
              const order = message.data as Order
              draft.orders.set(order.id, order)
            })

            // Show toast notification for important status changes
            if (message.data.status === 'COMPLETED') {
              toast.success(`Order #${message.data.id} completed!`, {
                duration: 5000,
              })
            } else if (message.data.status === 'CANCELLED') {
              toast.error(`Order #${message.data.id} was cancelled`, {
                duration: 5000,
              })
            }
            break

          case 'BALANCE_UPDATE':
            // Update balance in auth store if needed
            toast.success(`Balance updated: $${message.data.newBalance}`, {
              duration: 3000,
            })
            break

          case 'NOTIFICATION':
            set((draft) => {
              draft.notifications.unshift(message)
              // Keep only last 50 notifications
              if (draft.notifications.length > 50) {
                draft.notifications = draft.notifications.slice(0, 50)
              }
            })

            // Show toast for high priority notifications
            if (message.data.priority === 'high') {
              toast(message.data.message, {
                icon: message.data.icon || '📢',
                duration: 6000,
              })
            }
            break

          case 'SYSTEM_MESSAGE':
            // Handle system-wide messages
            if (message.data.type === 'maintenance') {
              toast.error(`System maintenance: ${message.data.message}`, {
                duration: 10000,
              })
            } else if (message.data.type === 'announcement') {
              toast(`📢 ${message.data.message}`, {
                duration: 8000,
              })
            }
            break

          default:
            console.log('Unknown WebSocket message type:', message.type)
        }
      },

      clearNotifications: () => {
        set((draft) => {
          draft.notifications = []
        })
      },

      markNotificationAsRead: (id: string) => {
        set((draft) => {
          const notification = draft.notifications.find(n => n.timestamp === id)
          if (notification && notification.data) {
            notification.data.read = true
          }
        })
      },
    }
  })
)

// Selectors for easier component consumption
export const useWebSocket = () => {
  const { isConnected, isConnecting, lastMessage } = useWebSocketStore()
  return { isConnected, isConnecting, lastMessage }
}

export const useWebSocketActions = () => {
  const { connect, disconnect, sendMessage } = useWebSocketStore()
  return { connect, disconnect, sendMessage }
}

export const useOrderUpdates = () => {
  const { orders } = useWebSocketStore()
  return { orders: Array.from(orders.values()) }
}

export const useNotifications = () => {
  const { notifications, clearNotifications, markNotificationAsRead } = useWebSocketStore()
  return { 
    notifications: notifications.filter(n => !n.data?.read),
    allNotifications: notifications,
    clearNotifications, 
    markNotificationAsRead 
  }
}
