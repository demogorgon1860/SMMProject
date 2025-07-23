import { format, formatDistanceToNow, parseISO, isValid } from 'date-fns'

/**
 * Format currency values
 */
export const formatCurrency = (
  amount: number,
  currency: string = 'USD',
  locale: string = 'en-US'
): string => {
  try {
    return new Intl.NumberFormat(locale, {
      style: 'currency',
      currency,
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(amount)
  } catch (error) {
    console.error('Currency formatting error:', error)
    return `$${amount.toFixed(2)}`
  }
}

/**
 * Format large numbers with abbreviations (K, M, B)
 */
export const formatNumber = (
  num: number,
  locale: string = 'en-US',
  compact: boolean = false
): string => {
  try {
    if (compact && Math.abs(num) >= 1000) {
      return new Intl.NumberFormat(locale, {
        notation: 'compact',
        compactDisplay: 'short',
        maximumFractionDigits: 1,
      }).format(num)
    }

    return new Intl.NumberFormat(locale).format(num)
  } catch (error) {
    console.error('Number formatting error:', error)
    return num.toString()
  }
}

/**
 * Format percentage values
 */
export const formatPercentage = (
  value: number,
  decimals: number = 1,
  locale: string = 'en-US'
): string => {
  try {
    return new Intl.NumberFormat(locale, {
      style: 'percent',
      minimumFractionDigits: decimals,
      maximumFractionDigits: decimals,
    }).format(value / 100)
  } catch (error) {
    console.error('Percentage formatting error:', error)
    return `${value.toFixed(decimals)}%`
  }
}

/**
 * Format dates with various options
 */
export const formatDate = (
  date: string | Date,
  formatType: 'full' | 'short' | 'relative' | 'time' | string = 'short',
  locale: string = 'en-US'
): string => {
  try {
    let dateObj: Date

    if (typeof date === 'string') {
      dateObj = parseISO(date)
    } else {
      dateObj = date
    }

    if (!isValid(dateObj)) {
      return 'Invalid date'
    }

    switch (formatType) {
      case 'full':
        return format(dateObj, 'PPpp')
      case 'short':
        return format(dateObj, 'MMM dd, yyyy')
      case 'time':
        return format(dateObj, 'HH:mm')
      case 'relative':
        return formatDistanceToNow(dateObj, { addSuffix: true })
      default:
        return format(dateObj, formatType)
    }
  } catch (error) {
    console.error('Date formatting error:', error)
    return 'Invalid date'
  }
}

/**
 * Format file sizes
 */
export const formatFileSize = (bytes: number, decimals: number = 2): string => {
  if (bytes === 0) return '0 Bytes'

  const k = 1024
  const dm = decimals < 0 ? 0 : decimals
  const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB', 'PB', 'EB', 'ZB', 'YB']

  const i = Math.floor(Math.log(bytes) / Math.log(k))

  return parseFloat((bytes / Math.pow(k, i)).toFixed(dm)) + ' ' + sizes[i]
}

/**
 * Format duration (seconds to human readable)
 */
export const formatDuration = (seconds: number): string => {
  if (seconds < 60) {
    return `${seconds}s`
  }

  const minutes = Math.floor(seconds / 60)
  const remainingSeconds = seconds % 60

  if (minutes < 60) {
    return remainingSeconds > 0 ? `${minutes}m ${remainingSeconds}s` : `${minutes}m`
  }

  const hours = Math.floor(minutes / 60)
  const remainingMinutes = minutes % 60

  if (hours < 24) {
    return remainingMinutes > 0 ? `${hours}h ${remainingMinutes}m` : `${hours}h`
  }

  const days = Math.floor(hours / 24)
  const remainingHours = hours % 24

  return remainingHours > 0 ? `${days}d ${remainingHours}h` : `${days}d`
}

/**
 * Truncate text with ellipsis
 */
export const truncateText = (text: string, length: number = 50): string => {
  if (text.length <= length) return text
  return text.slice(0, length) + '...'
}

/**
 * Format YouTube URL to extract video ID
 */
export const extractYouTubeVideoId = (url: string): string | null => {
  try {
    const regExp = /^.*(youtu.be\/|v\/|u\/\w\/|embed\/|watch\?v=|&v=)([^#&?]*).*/
    const match = url.match(regExp)
    return match && match[2].length === 11 ? match[2] : null
  } catch (error) {
    console.error('YouTube URL parsing error:', error)
    return null
  }
}

/**
 * Format YouTube URL for display
 */
export const formatYouTubeUrl = (url: string, maxLength: number = 50): string => {
  try {
    const videoId = extractYouTubeVideoId(url)
    if (videoId) {
      return `youtube.com/watch?v=${videoId}`
    }
    return truncateText(url, maxLength)
  } catch (error) {
    return truncateText(url, maxLength)
  }
}

/**
 * Format API key for display (show only first and last few characters)
 */
export const formatApiKey = (apiKey: string): string => {
  if (apiKey.length <= 8) return apiKey
  const start = apiKey.slice(0, 4)
  const end = apiKey.slice(-4)
  const middle = '*'.repeat(Math.min(8, apiKey.length - 8))
  return `${start}${middle}${end}`
}

/**
 * Format phone number
 */
export const formatPhoneNumber = (phoneNumber: string): string => {
  try {
    const cleaned = phoneNumber.replace(/\D/g, '')
    const match = cleaned.match(/^(\d{3})(\d{3})(\d{4})$/)
    
    if (match) {
      return `(${match[1]}) ${match[2]}-${match[3]}`
    }
    
    return phoneNumber
  } catch (error) {
    return phoneNumber
  }
}

/**
 * Format crypto amount with proper decimals
 */
export const formatCryptoAmount = (
  amount: number,
  currency: string,
  locale: string = 'en-US'
): string => {
  try {
    const decimals = currency === 'BTC' ? 8 : currency === 'ETH' ? 6 : 2
    
    return new Intl.NumberFormat(locale, {
      minimumFractionDigits: decimals,
      maximumFractionDigits: decimals,
    }).format(amount) + ` ${currency}`
  } catch (error) {
    return `${amount} ${currency}`
  }
}

/**
 * Format order status for display
 */
export const formatOrderStatus = (status: string): string => {
  const statusMap: Record<string, string> = {
    'PENDING': 'Pending',
    'IN_PROGRESS': 'In Progress',
    'PROCESSING': 'Processing',
    'ACTIVE': 'Active',
    'PARTIAL': 'Partial',
    'COMPLETED': 'Completed',
    'CANCELLED': 'Cancelled',
    'PAUSED': 'Paused',
    'HOLDING': 'Holding',
    'REFILL': 'Refill',
  }
  
  return statusMap[status] || status
}

/**
 * Format user role for display
 */
export const formatUserRole = (role: string): string => {
  const roleMap: Record<string, string> = {
    'USER': 'User',
    'OPERATOR': 'Operator',
    'ADMIN': 'Administrator',
  }
  
  return roleMap[role] || role
}

/**
 * Format input for search (remove special characters, normalize)
 */
export const normalizeSearchInput = (input: string): string => {
  return input
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '') // Remove diacritics
    .replace(/[^\w\s]/g, '') // Remove special characters
    .trim()
}

/**
 * Generate color for charts based on index
 */
export const getChartColor = (index: number): string => {
  const colors = [
    '#3b82f6', // blue-500
    '#10b981', // emerald-500
    '#f59e0b', // amber-500
    '#ef4444', // red-500
    '#8b5cf6', // violet-500
    '#06b6d4', // cyan-500
    '#84cc16', // lime-500
    '#f97316', // orange-500
    '#ec4899', // pink-500
    '#6366f1', // indigo-500
  ]
  
  return colors[index % colors.length]
}

/**
 * Format validation errors for display
 */
export const formatValidationErrors = (errors: Record<string, string[]>): string[] => {
  const formattedErrors: string[] = []
  
  Object.entries(errors).forEach(([field, fieldErrors]) => {
    fieldErrors.forEach(error => {
      const fieldName = field.charAt(0).toUpperCase() + field.slice(1).replace(/([A-Z])/g, ' $1')
      formattedErrors.push(`${fieldName}: ${error}`)
    })
  })
  
  return formattedErrors
}

/**
 * Format remaining time for deposits/orders
 */
export const formatRemainingTime = (expiresAt: string): string => {
  try {
    const expiryDate = parseISO(expiresAt)
    const now = new Date()
    
    if (expiryDate <= now) {
      return 'Expired'
    }
    
    const timeDiff = expiryDate.getTime() - now.getTime()
    const minutes = Math.floor(timeDiff / 60000)
    const hours = Math.floor(minutes / 60)
    const days = Math.floor(hours / 24)
    
    if (days > 0) {
      return `${days}d ${hours % 24}h remaining`
    } else if (hours > 0) {
      return `${hours}h ${minutes % 60}m remaining`
    } else {
      return `${minutes}m remaining`
    }
  } catch (error) {
    return 'Invalid date'
  }
}
